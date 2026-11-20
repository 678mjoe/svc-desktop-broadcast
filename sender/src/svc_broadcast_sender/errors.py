"""Safe, locally defined diagnostics; never format network input or credentials."""


class SenderError(Exception):
    def __init__(self, code: str, message: str, exit_code: int = 4):
        super().__init__(message)
        self.code = code
        self.message = message
        self.exit_code = exit_code


class InputError(SenderError):
    def __init__(self, code: str, message: str):
        super().__init__(code, message, 2)
