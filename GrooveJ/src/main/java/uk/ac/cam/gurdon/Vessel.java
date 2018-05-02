package uk.ac.cam.gurdon;

import java.awt.Color;

import ij.gui.Roi;
import ij.process.ImageStatistics;

public class Vessel {
	public Roi roi;
	public ImageStatistics depthStats;
	public Color depthColour;
	private double maxDepth;
	
	public Vessel(Roi roi, ImageStatistics stats, double maxDepth){
		this.roi = roi;
		this.depthStats = stats;
		this.maxDepth = maxDepth;
		getDepthColour();
	}
	
	public Color getDepthColour(){
		if(depthColour==null){
			float f = (float) ((depthStats.mean/maxDepth));
			f = (float) Math.max( 0d, Math.min(1f, f) );
			float r = f;
			float g = 0.2f;
			float b = 1f-f;
			depthColour = new Color(r, g, b);
		}
		roi.setStrokeColor(depthColour);
		return depthColour;
	}
	
}
