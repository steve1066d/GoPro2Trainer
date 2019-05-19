/*
 * Copyright (C) 2019 Steve Devore <mncyclist66@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package devore.gopro2trainer;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import static java.nio.charset.StandardCharsets.US_ASCII;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * This is a rather minimal class to retrieve GPS data from a GoPro GPMF file.
 * It does not attempt to be a generic GPMF parser.
 */
public class GPMF {
    private final ArrayList<Point> list = new ArrayList<>();
    
    public GPMF() {
    }

    public List<Point> getPoints() throws IOException {
        return list;
    }
    
    public void readStream(ByteBuffer buffer) throws IOException {
        getData(buffer, null);
    }

    private Data getData(ByteBuffer buffer, GPS gps) throws IOException {
        while(true) {
            Data data = getData(buffer);
            if (data == null) {
                break;
            }
            switch (data.key) {
                case "DEVC":
                    getData(data.buffer, gps);
                case "STRM":
                    gps = new GPS();
                    getData(data.buffer, gps);
                    if (gps.time > 0) {
                        Point pt = new Point(gps.lat, gps.lon, gps.time, gps.altitude);
                        list.add(pt);
                    }
                    break;
                case "GPSU":
                    String time = data.getString();
                    gps.setTime(time);
                    break;
                case "GPS5":
                    gps.setCoords(data.readInt(), data.readInt(), data.readInt());
                    break;
            }
        }
        return null;
    }
    
    private Data getData(ByteBuffer is) throws IOException {
        if (!is.hasRemaining()) {
            return null;
        }
        byte[] buffer = new byte[4];
        is.get(buffer);
        String key = new String(buffer, US_ASCII);
        is.get(); //sampleType
        int sampleSize = is.get();
        int sampleCount = Short.toUnsignedInt(is.getShort());
        int byteSize = sampleSize * sampleCount;
        int mod = byteSize % 4;
        if (mod > 0) {
            byteSize += (4 - mod);
        }
        byte[] samples = new byte[byteSize];
        is.get(samples);
        return new Data(key, samples, sampleSize * sampleCount);
    }
    
    private static class Data {
        String key;
        ByteBuffer buffer;
        int size;
        DataInputStream dis;
        
        public Data(String key, byte[] data, int size) {
            this.key = key;
            this.buffer = ByteBuffer.wrap(data);
            this.size = size;
        }

        public String getString() {
            return new String(buffer.array(), 0, size, US_ASCII);
        }
        
        public int readInt() throws IOException {
            return buffer.getInt();
        }
        
        @Override
        public String toString() {
            return key+": "+size;
        }
    }
    
    private static class GPS {
        private static final DateTimeFormatter FORMATTER = 
            DateTimeFormatter.ofPattern ("yyMMddHHmmss.SSS").withZone(ZoneOffset.UTC);
        long time;
        double lat;
        double lon;
        double altitude;
        
        public void setTime(String time) {
            this.time = ZonedDateTime.parse(time, FORMATTER).toInstant().toEpochMilli();
        }

        public void setCoords(int lat, int lon, int altitude) throws IOException {
            this.lat = lat / 1E7;
            this.lon = lon / 1E7;
            this.altitude = altitude / 1E3;
        }
        
        @Override
        public String toString() {
            return Utils.formatDateTime(time)+" "+lat+" "+lon+" "+altitude;
        }
    }
}
