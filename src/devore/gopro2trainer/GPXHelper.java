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
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.io.FilenameUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class GPXHelper {
    private static final Logger logger = Logger.getLogger(GPXHelper.class.getName());
    private List<Point> pointList = new ArrayList<>();
    private final List<Range> ranges = new ArrayList<>();
    private final String baseName;
    private final List<String> sourceFiles = new ArrayList<>();
    private final HashMap<String,Long> startTimes = new HashMap<>();
    private double stopSpeed;
    private double startSpeed;
    
    /**
     * Loads gpx files from the directory given.  It will ignore any files with \"virt\" in the file name
     * 
     * @param files  the list of gpx files to process
     * @param baseOutputName
     * @param cmd the command line
     * @throws java.io.IOException
     * @throws org.xml.sax.SAXException
     * @throws javax.xml.parsers.ParserConfigurationException
     */
    public GPXHelper(List<File> files, String baseOutputName, CommandLine cmd) throws IOException, SAXException, ParserConfigurationException {
        this.baseName = baseOutputName;
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
            String path = f.getPath();
            Point lastPoint = null;
            logger.info("Load gps: "+f.toString());
            List<Point> filePointList = new ArrayList<>();
            if (f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                GoProMP4 mp4 = new GoProMP4(f);
                filePointList.addAll(mp4.getPoints());
            } else {
                sourceFiles.add(f.getName());
                Document gpx = readXML(f);
                NodeList trkptList = gpx.getElementsByTagName("trkpt");
                for (int i=0; i < trkptList.getLength();i++) {
                    Element trkpt = (Element) trkptList.item(i);
                    Point pt = new Point(trkpt);
                    if (lastPoint != null && lastPoint.timestamp >= pt.timestamp) {
                        throw new RuntimeException("Points out of order at "+i+" "+pt+" "+lastPoint);
                    }
                    lastPoint = pt;
                    filePointList.add(pt);
                }
            }
            long firstTimestamp = filePointList.get(0).timestamp;
            startTimes.put(FilenameUtils.removeExtension(f.getName()).toLowerCase(Locale.US), firstTimestamp);
            data.put(firstTimestamp, filePointList);
        }
        data.values().forEach((filePoints) -> {
            pointList.addAll(filePoints);
        });
        logger.log(Level.INFO, "pointsize: {0}", pointList.size());
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

    public void fixMissingUpdates() {
        // My garmin sometimes has large distance, repeated twice.  This scans looking for a section
        // that remained the same as the last log entry, then replaces it with halfway between the 2 points.
        for(int i=pointList.size()-2; i >=0; i--){
            Point pt1 = pointList.get(i);
            Point pt2 = pointList.get(i+1);
            double mph = pt2.getMPH(pt1);
            if (mph == 0) {
                pt1.lat = (pt1.lat + pt2.lat) / 2;
                pt1.lon = (pt1.lon + pt2.lon) / 2;
            }
        }
    }
    
    /**
     * This removes from the log any entries where there wasn't at least 1.5 mph of motion.  Once stopped
     * it requires, 3.0 mph to start back up.
     * It populates ranges with any ranges that should be removed from the video.  (Any stops less
     * than 2 seconds will assumed to be a fluke in the data and will not have the video adjusted
     */
    public void markSpots() {
        ranges.clear();
        Range lastRange = null;
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
                if ((lastRange != null) && (lastRange.end - lastRange.start) > 2000) {
                    ranges.add(lastRange);
                }
                lastRange = null;
            }
        }
        if ((lastRange != null)) {
            ranges.add(lastRange);
            pointList.remove(pointList.size()-1);
        }
        ranges.forEach((range) -> {
            logger.fine(range.toString());
        });

        long offset = 0;
        logger.info("range update info....");
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
    }
    
    public void validate() {
        for(int i=0; i < pointList.size()-1; i++){
            Point pt1 = pointList.get(i);
            Point pt2 = pointList.get(i+1);
            double mph = pt2.getMPH(pt1);
            if (mph < 1.5) {
                logger.log(Level.WARNING, String.format("warning:  %.1f at %s\n", mph, pt2.toString()));
            }
        }
    }
    
    /**
     * Smooths out the elevation numbers, as the elevation is rather jaggy.
     */
    public void smoothEleveation() {
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
    
    public static Document readXML(File xmlFile) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder;
        dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xmlFile);
        return doc;
    }

    public void populate(Document doc, Element trkseg) {
        pointList.forEach((pt) -> trkseg.appendChild(pt.toElement(doc)));
    }
    
    void trim(long startTime, long endTime) {
        
        logger.log(Level.INFO, "trim to:          {0} {1}", new Object[]{Utils.formatDateTime(startTime), Utils.formatDateTime(endTime)});
        logger.log(Level.INFO, "before trim: {0} {1} {2}", new Object[]{pointList.size(), Utils.formatDateTime(startTime()), Utils.formatDateTime(endTime())});
        // start at the closest second
        startTime -= 500;
        // end up to a second after the video endds
        endTime += 1000;
        for(int i=pointList.size()-1; i >=0; i--){
            Point pt = pointList.get(i);
            if (pt.timestamp >= endTime|| pt.timestamp <= startTime -1 ) {
                pointList.remove(i);
            }
        }
        logger.log(Level.INFO, "after trim:  {0} {1} {2}", new Object[]{pointList.size(), Utils.formatDateTime(startTime()), Utils.formatDateTime(endTime())});
    }

    /**
     * The GoPro saves 15 measures a second, which is more than we need.  Change it to some other interval
     * @param millis the desired millisecond precision.
     */
    void changePolling(long millis) {
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
}
