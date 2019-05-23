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

// code from flanagan.analysis from Dr Michael Thomas Flanagan at www.ee.ucl.ac.uk/~mflanaga 
// used by permission for non-commerical use.
import flanagan.analysis.CurveSmooth;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.io.FilenameUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public final class GPXHelper {
    private static final double MAX_STOP_DISTANCE  = 4 / 1609.344;  // 3 meters
    
    private static final Logger logger = Logger.getLogger(GPXHelper.class.getName());
    private List<Point> pointList = new ArrayList<>();
    private final List<Range> ranges = new ArrayList<>();
    private final String baseName;
    private final List<String> sourceFiles = new ArrayList<>();
    private final HashMap<String,Long> startTimes = new HashMap<>();
    private double stopSpeed;
    private double startSpeed;
    private CommandLine cmd;
    
    /**
     * Loads gps files from the directory given.
     * 
     * @param files  the list of gps files to process (mp4, gpx, and tcx accepted
     * @param baseOutputName the name of the output file, minus the extension
     * @param cmd the command line options
     * 
     * @throws java.io.IOException
     * @throws org.xml.sax.SAXException
     * @throws javax.xml.parsers.ParserConfigurationException
     */
    public GPXHelper(List<File> files, String baseOutputName, CommandLine cmd) throws IOException, SAXException, ParserConfigurationException {
        this.baseName = baseOutputName;
        this.cmd = cmd;
        stopSpeed = Double.parseDouble(cmd.getOptionValue("stopSpeed", "-1"));
        startSpeed = Double.parseDouble(cmd.getOptionValue("startSpeed", "-1"));
        if (!cmd.hasOption("mph")) {
            stopSpeed /= 1.609;
            startSpeed /= 1.609;
        }
        if (stopSpeed < 0) {
            stopSpeed = 3 / 1.609;
        }
        if (startSpeed < 0) {
            startSpeed = 6 / 1.609;
        }
        TreeMap<Long,List<Point>> data = new TreeMap<>();
        for (File f: files) {
            Point lastPoint = null;
            logger.log(Level.INFO, "Load gps files from: {0}", f.toString());
            if (!f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                sourceFiles.add(f.getName());
            }
            List<Point> filePointList = GpsLoader.load(f);
            for (Point pt : filePointList) {
                if (lastPoint != null && lastPoint.timestamp >= pt.timestamp) {
                    throw new RuntimeException("Points out of order at "+pt+" "+lastPoint);
                }
                lastPoint = pt;
            }
            long firstTimestamp = filePointList.get(0).timestamp;
            startTimes.put(FilenameUtils.removeExtension(f.getName()).toLowerCase(Locale.US), firstTimestamp);
            data.put(firstTimestamp, filePointList);
        }
        data.values().forEach((filePoints) -> {
            pointList.addAll(filePoints);
        });
        logger.log(Level.INFO, "Load      {0}", debug());
    }
    
    public String debug() {
        return String.format("GPS %s to %s, duration: %s)",
                Utils.formatDateTime(startTime()),
                Utils.formatDateTime(endTime()),
                Utils.formatElapsed(endTime() - startTime()));
    }
    
    public String getBaseName() {
        return baseName;
    }
    
    public List<String> getSourceFiles() {
        return sourceFiles;
    }
    
    List<Range> getCuts() {
        return ranges;
    }
    
    public long startTime() {
        return pointList.get(0).timestamp;
    }
    
    public long endTime() {
        return pointList.get(pointList.size() - 1).timestamp;
    }
    
    public List<Point> getPoints() {
        return pointList;
    }

    public void fixMissingUpdates() {
        // My Garmin sometimes has large distance, repeated twice.  This scans looking for a section
        // that remained the same as the last log entry, then replaces it with halfway between the 2 points.
        if (cmd.hasOption("fixMissing")) {
            for(int i=pointList.size()-2; i >=1; i--){
                Point pt = pointList.get(i);
                Point ptPrev = pointList.get(i-1);
                Point ptNext = pointList.get(i+1);
                if (pt.lat == ptPrev.lat && pt.lon == ptPrev.lon) {
                    pt.lat = (pt.lat + ptNext.lat) / 2;
                    pt.lon = (pt.lon + ptNext.lon) / 2;
                }
            }
        }
    }
    
    /**
     * This removes from the log any entries where there wasn't at least a minimum amount of motion.  Once stopped
     * it requires a certain speed to start back up. (default is 3 km/h and 6 km/h)
     * It populates ranges with any ranges that should be removed from the video.  (Any stops less
     * than 2 seconds will assumed to be a fluke in the data and will not have the video adjusted
     */
    public void removeBeginEnd() {
        logger.info("remove stops at beginning and ending");
        ranges.clear();
        // trim beginning
        for(int i=0; i < pointList.size()-1; i++){
            Point pt = pointList.get(i);
            double mph = pointList.get(i+1).getMPH(pt);
            if (mph < startSpeed) {
                pointList.remove(i);
                i--;
            } else {
                break;
            }
        }
        // trim ending
        for (int i=pointList.size()-1; i >0; i--) {
            Point pt = pointList.get(i-1);
            Point pt2 = pointList.get(i);
            double mph = pt2.getMPH(pt);
            if (mph < stopSpeed) {
                pointList.remove(i);
            } else {
                break;
            }
        }
        logger.log(Level.INFO, "trimmed:  {0}", debug());
    }
    
    public void markSpots() {
        Range lastRange = null;
        // Look for stops in the middle
        for(int i=0; i < pointList.size()-1; i++){
            Point pt1 = pointList.get(i);
            Point pt2 = pointList.get(i+1);
            double mph = pt2.getMPH(pt1);
            if (mph < stopSpeed || mph < startSpeed && lastRange != null) {
                if (lastRange != null) {
                    lastRange.end = pt2.timestamp;
                } else {
                    lastRange = new Range(pt1.timestamp, pt2.timestamp);
                }
                pointList.remove(i);
                i--;
            } else {
                if (lastRange != null) {
                    long searchTime = pt1.timestamp - 120000;
                    int startIdx = findIndex(searchTime);
                    int closestIdx = -1;
                    double closestDistance = Double.MAX_VALUE;
                    for (int j=startIdx; j <= i; j++) {
                        double dist = pointList.get(j).getMiles(pt1);
                        if (dist < closestDistance) {
                            closestDistance = dist;
                            closestIdx = j;
                        } else if (j < i-1 && closestDistance < MAX_STOP_DISTANCE) { 
                            // distance is starting to go up, and we are close
                            break;
                        }
                    }
                    if (closestIdx < i) {
                        closestIdx--;
                        logger.info("Removing backtrack");
                        Point bt1 = pointList.get(closestIdx);
                        Point bt2 = pointList.get(i);
                        logger.info(String.format(" %s to %s, dur: %s", 
                                Utils.formatDateTime(bt1.timestamp), 
                                Utils.formatDateTime(bt2.timestamp), 
                                Utils.formatElapsed(bt2.timestamp-bt1.timestamp)));
                        lastRange.start = pointList.get(closestIdx).timestamp;
                        for (int j = i; j >= closestIdx; j--) {
                            pointList.remove(j);
                            i--;
                        }
                    }
                    if ((lastRange.end - lastRange.start) > 2000) {
                        ranges.add(lastRange);
                    }
                }
                lastRange = null;
            }
        }
        if ((lastRange != null)) {
            ranges.add(lastRange);
            pointList.remove(pointList.size()-1);
        }
        // check for overlaps.
        ArrayList<Range> newList = new ArrayList<>();
        Range lastR = null;
        for (Range r: ranges) {
            if (lastR == null) {
                lastR = r;
            } else {
                if (r.start >= lastR.start) {
                    newList.add(lastR);
                }
                lastR = r;
            }
        }
        newList.add(lastR);
        ranges.clear();
        ranges.addAll(newList);

        ranges.forEach((range) -> {
            logger.fine(range.toString());
        });

        long offset = 0;
        logger.info("Stop ranges found:");
        for (Range range : ranges) {
            offset += range.getDuration();
            range.offset = offset;
            logger.info(range.toString());
        }
    }
    
    /**
     * This accounts for any stops from the gps file by adjusting the timestamps
     */
    public void removeStops() {
        logger.info("Remove stops");
        for(int i=0; i < pointList.size();i++){
            Point pt = pointList.get(i);
            Range find = new Range(pt.timestamp, pt.timestamp);
            int pos = Collections.binarySearch(ranges, find, null);
            if (pos <  0) {
                pos = -pos - 2;
            }
            if (pos >= 0) {
                Range found = ranges.get(pos);
                pt.timestamp -= found.offset;
                if (i > 0 && pt.timestamp <= pointList.get(i-1).timestamp) {
                    throw new RuntimeException("Points out of order at "+i+" "+pt+" "+pointList.get(i-1));
                }
            }
        }
        logger.info("nostops:  "+debug());
    }
    
    public void validate() {
        for(int i=0; i < pointList.size()-1; i++){
            Point pt1 = pointList.get(i);
            Point pt2 = pointList.get(i+1);
            double mph = pt2.getMPH(pt1);
            if (mph < 1.5) {
                logger.log(Level.WARNING, String.format("warning:  %.1f at %s", mph, pt2.toString()));
            }
        }
    }
    
    /**
     * Smooths out the elevation numbers, as the elevation is rather jaggy.
     */
    public void smoothEleveation() {
        logger.info("smoothElevation");
        double[] time =  new double[pointList.size()];
        double[] elevation = new double[pointList.size()];
        for (int i=pointList.size()-1; i >=0; i--){
            Point pt = pointList.get(i);
            elevation[i] = pt.elevation;
            time[i] = pt.timestamp;
        }
        CurveSmooth smooth = new CurveSmooth(time, elevation);
        double[] filtOutput = smooth.savitzkyGolay(47);
        for (int i=pointList.size()-1; i >=0; i--){
            Point pt = pointList.get(i);
            pt.elevation = filtOutput[i];
        }
    }
    
    public void populate(Document doc, Element trkseg) {
        pointList.forEach((pt) -> trkseg.appendChild(pt.toElement(doc)));
    }
    
    void trimToVideo(long startTime, long endTime) {
        // start at the closest second
        startTime -= 500;
        // end up to a second after the video ends
        endTime += 1000;
        //logger.log(Level.INFO, "trim to:          {0} {1}", new Object[]{Utils.formatDateTime(startTime), Utils.formatDateTime(endTime)});
//        logger.log(Level.INFO, "before trim: {0} {1} {2}", new Object[]{pointList.size(), Utils.formatDateTime(startTime()), Utils.formatDateTime(endTime())});
        for(int i=pointList.size()-1; i >=0; i--){
            Point pt = pointList.get(i);
            if (pt.timestamp >= endTime|| pt.timestamp <= startTime -1 ) {
                pointList.remove(i);
            }
        }
        logger.log(Level.INFO, "cut:      "+debug());
    }

    /**
     * The GoPro saves 15 measures a second, which is more than we need.  Change it to once per second
     * @param millis the desired millisecond precision.
     */
    void changePolling(long millis) {
        logger.info("Change polling");
        long maxDelta = millis / 4;
        List<Point> newList = new ArrayList<>();
        long lastTimestamp = 0;
        for (Point pt : pointList) {
            if (pt.timestamp >= lastTimestamp + millis) {
                newList.add(pt);
                lastTimestamp += millis;
                if (Math.abs(lastTimestamp - pt.timestamp) > maxDelta) {
                    lastTimestamp = pt.timestamp;
                }
            }
        }
        pointList = newList;
        logger.log(Level.INFO, "new point count: {0}", pointList.size());
    }

    private int findIndex(long searchTime) {
        Point compare = new Point(0.0,0.0, searchTime, 0);
        int pos = Collections.binarySearch(pointList, compare);
        if (pos < 0) {
            pos = -pos + 1;
        }
        return pos;
    }

    /**
     * Replaces elevation data with data from this file
     * 
     * @param cmd CommandLine
     */
    void fixElevations() throws IOException, SAXException, ParserConfigurationException {
        String elevation = cmd.getOptionValue("elevation");
        if (elevation != null) {
            logger.info("Fixing elevations");
            File elevationDir = new File(elevation);
            List<File> elevationList = Collections.singletonList(elevationDir);
            GPXHelper elevationHelper = new GPXHelper(elevationList, null, cmd);
            List<Point> points = elevationHelper.getPoints();
            long time = System.currentTimeMillis();
            // not very efficient, but computers are fast.  
            // though we can do this parallel, which will speed things up a bit.
            pointList.parallelStream().forEach((pt) -> {
                double closestSq = Double.MAX_VALUE;
                Point closestPt = null;
                for (Point testPt : points) {
                    double test = Point2D.distanceSq(pt.lat, pt.lon, testPt.lat, testPt.lon);
                    if (test < closestSq) {
                        closestSq = test;
                        closestPt = testPt;
                    }
                }
                pt.elevation = closestPt.elevation;
            });
            logger.log(Level.INFO, "Fixed elevations. Took {0}", System.currentTimeMillis() - time);
        }
    }

    /**
     * This will skew the entire trip by a certain percentage making it harder or easier.
     *
     * @param cmd 
     */
    void changeSlope() {
        String slopeStr = cmd.getOptionValue("slope");
        if (slopeStr != null) {
            double mult = 1;
            if (slopeStr.endsWith("%")) {
                slopeStr = slopeStr.substring(0, slopeStr.length()-1);
                mult = .01;
            }
            double totalSlope = 0;
            double slope = Double.parseDouble(slopeStr) * mult;
            Point last = pointList.get(0);
            for (int i=1; i < pointList.size(); i++) {
                Point pt = pointList.get(i);
                double miles = pt.getMiles(last);
                
                totalSlope += (slope * miles /  Point.METERS_TO_MILES);
                pt.elevation += totalSlope;
                last = pt;
            }
            logger.info(String.format("Setting slope: %.1f%%, total change %.0f meters", slope*100, totalSlope));
        }
    }
}
