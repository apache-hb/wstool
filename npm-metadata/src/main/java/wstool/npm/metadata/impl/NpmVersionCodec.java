package wstool.npm.metadata.impl;

import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import wstool.npm.metadata.NpmVersion;

/** Maps npm semantic versions from their string representation. */
public final class NpmVersionCodec extends AbstractJsonValueCodec<NpmVersion> {
    @Override
    public void write(JsonWriter writer, NpmVersion value) {
        if (value == null) {
            writer.writeNull();
        } else {
            writer.writeString(value.value());
        }
    }

    @Override
    public NpmVersion read(JsonReader reader) {
        if (reader.tryReadNullToken()) {
            return null;
        }
        return new NpmVersion(reader.readString());
    }
}
