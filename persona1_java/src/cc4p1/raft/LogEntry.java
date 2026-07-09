package cc4p1.raft;

import cc4p1.common.ProtocolMessage;


public class LogEntry {

    public final long index;
    public final long term;
    public final int camera;
    public final String clase;
    public final String timestamp;
    public final String imagePath;

    public LogEntry(long index, long term, int camera, String clase, String timestamp, String imagePath) {
        this.index = index;
        this.term = term;
        this.camera = camera;
        this.clase = clase;
        this.timestamp = timestamp;
        this.imagePath = imagePath;
    }

    public String encode() {
        String s = ProtocolMessage.ENTRY_FIELD_SEP;
        return index + s + term + s + camera + s + clase + s + timestamp + s + imagePath;
    }

    public static LogEntry decode(String raw) {
        String[] f = raw.split(ProtocolMessage.ENTRY_FIELD_SEP, 6);
        return new LogEntry(
                Long.parseLong(f[0]),
                Long.parseLong(f[1]),
                Integer.parseInt(f[2]),
                f[3],
                f[4],
                f[5]
        );
    }

    @Override
    public String toString() {
        return "#" + index + " (term " + term + ") camara=" + camera + " clase=" + clase
                + " hora=" + timestamp + " img=" + imagePath;
    }
}
