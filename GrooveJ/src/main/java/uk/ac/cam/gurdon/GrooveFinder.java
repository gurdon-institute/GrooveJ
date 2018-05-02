package uk.ac.cam.gurdon;

import java.util.ArrayList;
import java.util.Collections;

public class GrooveFinder {
	
	private static class RangeStats{
		double sum, mean, stdDev, median;
		RangeStats(double[] data, int i0, int i1){
			sum = 0d;
		    for(int d=i0;d<i1;d++){
		    	sum += data[d];
		    }
		    mean = sum/(double)(i1-i0);
		    
		    double ssd = 0d;
		    ArrayList<Double> list = new ArrayList<Double>();
		    for(int d=i0;d<i1;d++){
		    	ssd += ((data[d]-mean)*(data[d]-mean));
		    	list.add(data[d]);
		    }
		    Collections.sort(list);
		    median = list.get(list.size()/2);
		    double var = ssd/((double)(i1-i0)-1);
		    stdDev = Math.sqrt(var);
		}
	}
	
	public static int[] find(double[] data, int lag, double nStdDevs){
	    int[] response = new int[data.length];
	    RangeStats statsA = new RangeStats(data, 0, lag);
	    double rollingAvg = statsA.median;
	    double rollingSd = statsA.stdDev;
	    for (int i=lag;i<data.length;i++){
	    	double localDelta = data[i] - rollingAvg;
	    	double thresholdZ = nStdDevs * rollingSd;
	        if ( localDelta > thresholdZ){
	            response[i] = 1;
	        }
	        else if ( localDelta < -thresholdZ){
	            response[i] = -1;
	        }
	        else{
	            response[i] = 0;
	        }
	        RangeStats stats = new RangeStats(data, i-lag, i);
            rollingAvg = stats.median;
            rollingSd = stats.stdDev;
	    }
	    return response;
	}
	
}
