package page.mjoe.svcbroadcast;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.StringReader;
import java.math.BigDecimal;

/** Reject ambiguous/duplicate local authorization fields rather than defaulting permissions. */
final class StrictJson {
    static JsonElement parse(String text) throws Exception {
        try (var reader=new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value=value(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException();
            return value;
        }
    }
    private static JsonElement value(JsonReader reader,int depth) throws Exception {
        if(depth>10)throw new IllegalArgumentException();
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object=new JsonObject();reader.beginObject();
                while(reader.hasNext()) {
                    String name=reader.nextName();if(object.has(name))throw new IllegalArgumentException();
                    object.add(name,value(reader,depth+1));
                }
                reader.endObject();yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array=new JsonArray();reader.beginArray();
                while(reader.hasNext())array.add(value(reader,depth+1));
                reader.endArray();yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            default -> throw new IllegalArgumentException();
        };
    }
}
