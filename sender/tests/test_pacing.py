import asyncio
from uuid import UUID

import pytest

from svc_broadcast_sender import transport
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.protocol import FRAME_PERIOD_NS, PCM_BYTES
from svc_broadcast_sender.transport import UploadResult, stream_audio

SESSION = UUID("00112233-4455-6677-8899-aabbccddeeff")


class FakeClock:
    def __init__(self, overshoot=None):
        self.now = 0
        self.overshoot = dict(overshoot or {})

    def now_ns(self):
        return self.now

    async def sleep_until(self, deadline_ns):
        self.now = max(self.now, deadline_ns) + self.overshoot.pop(deadline_ns, 0)
        await asyncio.sleep(0)


class IndexedSource:
    def __init__(self, count, clock, read_delays=None):
        self.frame_count = count
        self.clock = clock
        self.read_delays = dict(read_delays or {})
        self.reads = []

    def read_frame(self, sequence):
        self.reads.append(sequence)
        self.clock.now += self.read_delays.pop(sequence, 0)
        return sequence.to_bytes(2, "little") * (PCM_BYTES // 2)


def _stream(count, *, send_delays=None, read_delays=None, overshoot=None):
    async def scenario():
        clock = FakeClock(overshoot)
        source = IndexedSource(count, clock, read_delays)
        delays = dict(send_delays or {})
        sent = []

        async def send(packet):
            sequence = int.from_bytes(packet[16:24], "big")
            assert packet[:16] == SESSION.bytes
            assert packet[24:] == sequence.to_bytes(2, "little") * 960
            sent.append((sequence, clock.now))
            clock.now += delays.pop(sequence, 0)

        result = await stream_audio(source, SESSION, send, clock=clock)
        return result, sent, source.reads

    return asyncio.run(scenario())


def test_absolute_clock_does_not_accumulate_send_time():
    result, sent, reads = _stream(6, send_delays={i: 7_000_000 for i in range(6)})
    assert result == UploadResult(6, 0)
    assert sent == [(i, i * FRAME_PERIOD_NS) for i in range(6)]
    assert reads == list(range(6))


def test_backpressure_skips_source_frames_without_catchup_burst():
    result, sent, reads = _stream(8, send_delays={0: 105_000_000})
    assert result == UploadResult(4, 4)
    assert sent == [(0, 0), (5, 105_000_000), (6, 120_000_000), (7, 140_000_000)]
    assert reads == [0, 5, 6, 7]


def test_scheduler_oversleep_drops_frames_before_reading_them():
    result, sent, reads = _stream(5, overshoot={20_000_000: 45_000_000})
    assert result == UploadResult(3, 2)
    assert sent == [(0, 0), (3, 65_000_000), (4, 80_000_000)]
    assert reads == [0, 3, 4]


def test_audio_that_becomes_stale_during_read_is_not_sent():
    result, sent, reads = _stream(5, read_delays={1: 45_000_000})
    assert result == UploadResult(3, 2)
    assert [sequence for sequence, _ in sent] == [0, 3, 4]
    assert reads == [0, 1, 3, 4]


def test_first_frame_stays_zero_after_slow_initial_read():
    result, sent, _ = _stream(2, read_delays={0: 100_000_000})
    assert result == UploadResult(2, 0)
    assert sent == [(0, 100_000_000), (1, 120_000_000)]


def test_pause_past_eof_drops_remaining_audio_without_replay():
    result, sent, reads = _stream(4, send_delays={0: 500_000_000})
    assert result == UploadResult(1, 3)
    assert sent == [(0, 0)]
    assert reads == [0]


def test_send_failure_stops_source_reads_immediately():
    async def scenario():
        source = IndexedSource(20, FakeClock())

        async def failed_send(packet):
            raise SenderError("send_timeout", "stalled", 5)

        with pytest.raises(SenderError):
            await stream_audio(source, SESSION, failed_send, clock=source.clock)
        assert source.reads == [0]

    asyncio.run(scenario())


@pytest.mark.parametrize("silent", [False, True])
def test_live_callback_slower_than_frame_period_keeps_sending(silent):
    """Device reads crossing a 20 ms boundary must not starve the wire forever."""
    class FinishedProbe(Exception):
        pass

    async def scenario():
        clock = FakeClock()

        class LiveSource:
            frame_count = None
            reads = 0

            def read_frame(self, sequence):
                if self.reads == 151:
                    raise FinishedProbe()
                self.reads += 1
                if sequence:
                    clock.now += 21_000_000
                return bytes(PCM_BYTES) if silent else b"\x01\x00" * 960

        sent = []

        async def send(packet):
            sent.append((int.from_bytes(packet[16:24], "big"), clock.now))

        with pytest.raises(FinishedProbe):
            await stream_audio(LiveSource(), SESSION, send, clock=clock)
        assert len(sent) == 151
        assert sent[0] == (0, 0)
        assert all(b[0] > a[0] and 20_000_000 <= b[1] - a[1] <= 41_000_000
                   for a, b in zip(sent, sent[1:]))
        assert all(sequence <= at // FRAME_PERIOD_NS for sequence, at in sent)

    asyncio.run(scenario())


def test_live_stalled_read_is_discarded_then_recovers_without_burst():
    class FinishedProbe(Exception):
        pass

    async def scenario():
        clock = FakeClock()

        class LiveSource:
            frame_count = None
            reads = 0

            def read_frame(self, sequence):
                index = self.reads
                self.reads += 1
                if index == 5:
                    raise FinishedProbe()
                if index == 1:
                    clock.now += 500_000_000
                return index.to_bytes(2, "little") * 960

        sent = []

        async def send(packet):
            sent.append((int.from_bytes(packet[24:26], "little"), clock.now))

        with pytest.raises(FinishedProbe):
            await stream_audio(LiveSource(), SESSION, send, clock=clock)
        assert [source_index for source_index, _ in sent] == [0, 2, 3, 4]
        assert all(b[1] - a[1] >= FRAME_PERIOD_NS for a, b in zip(sent, sent[1:]))

    asyncio.run(scenario())


def test_live_scheduler_delay_skips_ticks_without_accumulating_drift():
    class FinishedProbe(Exception):
        pass

    async def scenario():
        clock = FakeClock({20_000_000: 105_000_000})

        class LiveSource:
            frame_count = None

            def read_frame(self, sequence):
                if sequence == 4:
                    raise FinishedProbe()
                return bytes(PCM_BYTES)

        sent = []

        async def send(packet):
            sent.append((int.from_bytes(packet[16:24], "big"), clock.now))

        with pytest.raises(FinishedProbe):
            await stream_audio(LiveSource(), SESSION, send, clock=clock)
        assert sent == [(0, 0), (6, 125_000_000), (7, 140_000_000), (8, 160_000_000)]

    asyncio.run(scenario())


class StalledConnection:
    def __init__(self):
        self.transport = self
        self.aborted = False
        self.entered = asyncio.Event()

    async def send(self, message):
        self.entered.set()
        await asyncio.Future()

    def abort(self):
        self.aborted = True


def test_send_deadline_aborts_buffered_transport(monkeypatch):
    monkeypatch.setattr(transport, "SEND_TIMEOUT", 0)

    async def scenario():
        connection = StalledConnection()
        with pytest.raises(SenderError) as error:
            await transport._bounded_send(connection, bytes(1944))
        assert error.value.code == "send_timeout"
        assert error.value.exit_code == 5
        assert connection.aborted

    asyncio.run(scenario())


def test_cancellation_during_send_aborts_instead_of_replaying():
    async def scenario():
        connection = StalledConnection()
        sending = asyncio.create_task(transport._bounded_send(connection, bytes(1944)))
        await connection.entered.wait()
        sending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await sending
        assert connection.aborted

    asyncio.run(scenario())
