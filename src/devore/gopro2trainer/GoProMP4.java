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

import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import static java.nio.charset.StandardCharsets.US_ASCII;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class to read the metadata and gps data from a gopro MP4.
 */
public class GoProMP4 {
    private static final Logger logger = Logger.getLogger(GoProMP4.class.getName());
    private static final DateTimeFormatter FORMAT = 
        DateTimeFormatter.ofPattern ("uuuuMMddHHmmss.SSS").withZone(ZoneOffset.UTC);
    private final File file;
    private long creationTime;
    private long duration;
    private List<Point> points;
    private Track track;
    
    public GoProMP4(File f) throws IOException {
        this.file = f;
        Movie mWithVideo = MovieCreator.build(f.toString());
        for (Track trk : mWithVideo.getTracks()) {
            if (trk.getHandler().equals("vide")) {
                duration = trk.getDuration() * 1000 / trk.getTrackMetaData().getTimescale();
                // GoPro saves time as localtime instead of UTC
                Instant instant = trk.getTrackMetaData().getCreationTime().toInstant();
                LocalDateTime ldt = LocalDateTime.parse(FORMAT.format(instant), FORMAT);
                creationTime = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            if (trk.getHandler().equals("meta")) {
                Sample s = trk.getSamples().get(0);
                byte[] buffer = new byte[4];
                s.asByteBuffer().get(buffer);
                if (new String(buffer, US_ASCII).equals("DEVC")) {
                    this.track = trk;
                }
            }
        }
    }
    
    /**
     * Returns the start time of the mp4. It will use the first gps timestamp if available, otherwise
     * it will use the creation time from the mp4 metadata.
     */
    public long getTimestamp() throws IOException {
        readTrack(false);
        if (getPoints().isEmpty()) {
            return getCreationTime();
        } else {
            long gpsTimestamp = getPoints().get(0).timestamp;
            logger.log(Level.INFO, "{0} gps timestamp is {1}. Creation date is {2}", new Object[]{
                file.getName(), Utils.formatDateTime(gpsTimestamp), Utils.formatDateTime(creationTime)});
            return gpsTimestamp;
        }
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getDuration() {
        return duration;
    }
    
    public List<Point> getPoints() throws IOException {
        readTrack(true);
        return points;
    }

    private void readTrack(boolean readAll) throws IOException {
        if (points == null) {
            if (track != null) {
                GPMF gpmf = new GPMF();
                for (Sample sample : track.getSamples()) {
                    ByteBuffer buf = sample.asByteBuffer();
                    gpmf.readStream(buf);
                    if (!readAll && !gpmf.getPoints().isEmpty()) {
                        break;
                    }
                }
                points = gpmf.getPoints();
            } else {
                points = Collections.emptyList();
            }
        }
    }
}
