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

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Loads gps data. It supports gpx, tcx, and GoPro mp4 files
 */
public class GpsLoader {
    public static List<Point> load(File f) throws ParserConfigurationException, IOException, SAXException {
        String name = f.getName().toLowerCase(Locale.US);
        if (name.endsWith(".mp4")) {
            GoProMP4 mp4 = new GoProMP4(f);
            return mp4.getPoints();
        } else {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(f);
            if (name.endsWith(".gpx")) {
                return loadGPX(doc);
            } else if (name.endsWith(".tcx")) {
                return loadTCX(doc);
            } else {
                throw new RuntimeException("Unknown gps file type: "+f);
            }
        }
    }
    
    public static List<Point> loadTCX(Document doc) {
        ArrayList<Point> retVal = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("Trackpoint");
        System.out.println("nodes: "+nodes.getLength());
        long last = 0;
        for (int i=0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            Element child = (Element) element.getElementsByTagName("Time").item(0);
            long timestamp = Instant.parse(child.getTextContent()).toEpochMilli();
            if (timestamp == last) {
                continue;
            }
            last = timestamp;
            double lat = getDouble(element, "LatitudeDegrees");
            double lon = getDouble(element, "LongitudeDegrees");
            double elevation = getDouble(element, "AltitudeMeters");
            double speed = getDouble(element, "ns3:Speed") * Point.MS_TO_MPH;
            double power = getDouble(element, "ns3:Watts");
            Point pt = new Point(lat, lon, timestamp, elevation);
            pt.speed = speed;
            pt.power = power;
            retVal.add(pt);
        }
        return retVal;
    }
    
    public static List<Point> loadGPX(Document gpx) {
        List<Point> retVal = new ArrayList<>();
        NodeList trkptList = gpx.getElementsByTagName("trkpt");
        for (int i=0; i < trkptList.getLength();i++) {
            Element element = (Element) trkptList.item(i);
            double lat = Double.parseDouble(element.getAttribute("lat"));
            double lon = Double.parseDouble(element.getAttribute("lon"));
            Element ele = (Element) element.getElementsByTagName("time").item(0);
            String time = ele.getTextContent();
            long timestamp = Instant.parse(time).toEpochMilli();
            ele = (Element) element.getElementsByTagName("ele").item(0);
            double elevation = Double.parseDouble(ele.getTextContent());
            Point pt = new Point(lat, lon, timestamp, elevation);
            pt.power = getDouble(element, "power");
        }
        return retVal;
    }

    public static double getDouble(Element ele, String tag) {
        NodeList nodes = ele.getElementsByTagName(tag);
        if (nodes.getLength() > 0) {
            return Double.parseDouble(nodes.item(0).getTextContent());
        }
        return Double.NaN;
    }
}
