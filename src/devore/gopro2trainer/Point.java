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

import java.time.Instant;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class Point implements Comparable<Point> {
    double lat;
    double lon;
    long timestamp;
    double elevation;

    Point(Element element) {
        lat = Double.parseDouble(element.getAttribute("lat"));
        lon = Double.parseDouble(element.getAttribute("lon"));
        Element ele = (Element) element.getElementsByTagName("time").item(0);
        String time = ele.getTextContent();
        timestamp = Instant.parse(time).toEpochMilli();
        ele = (Element) element.getElementsByTagName("ele").item(0);
        elevation = Double.parseDouble(ele.getTextContent());
    }
    
    public Point(Double lat, Double lon, long timestamp, double elevation) {
        this.lat = lat;
        this.lon = lon;
        this.timestamp = timestamp;
        this.elevation = elevation;
    }

    @Override
    public String toString() {
        return Utils.formatDateTime(timestamp)+": "+lat+" "+lon+" "+elevation;
    }

    public double getMPH(Point lastPoint) {
        double miles = getMiles(lastPoint);
        return miles / ( (timestamp - lastPoint.timestamp) / 1000.0 / 60 / 60);
    }

    public double getMiles(Point lastPoint) {
        if ((lastPoint.lat == this.lat) && (lastPoint.lon == this.lon)) {
            return 0;
        } else {
            double theta = lastPoint.lon - this.lon;
            double dist = Math.sin(Math.toRadians(lastPoint.lat)) * Math.sin(Math.toRadians(this.lat)) + 
                    Math.cos(Math.toRadians(lastPoint.lat)) * Math.cos(Math.toRadians(this.lat)) * 
                    Math.cos(Math.toRadians(theta));
            dist = Math.toDegrees(Math.acos(dist));
            dist *= 60 * 1.1515;
            return dist;
        }
    }

    public String getTime() {
        return Instant.ofEpochMilli(timestamp).toString();            
    }

    public Element toElement(Document doc) {
        Element trkpt = doc.createElement("trkpt");
        trkpt.setAttribute("lat",String.format("%.7f", lat));
        trkpt.setAttribute("lon",String.format("%.7f", lon));
        Element ele = doc.createElement("ele");
        ele.setTextContent(String.format("%.2f", elevation));
        Element time = doc.createElement("time");
        time.setTextContent(getTime());
        trkpt.appendChild(ele);
        trkpt.appendChild(time);
        return trkpt;
    }

    @Override
    public int compareTo(Point o) {
        return Long.compare(this.timestamp, o.timestamp);
    }
}
