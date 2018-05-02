package uk.ac.cam.gurdon;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.DecimalFormat;
import java.text.Format;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYAnnotation;
import org.jfree.chart.annotations.XYBoxAnnotation;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.data.general.Series;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import fiji.threshold.Auto_Local_Threshold;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.HyperStackConverter;
import ij.plugin.Straightener;
import ij.plugin.ZProjector;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.MaximumFinder;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

@Plugin(type = Command.class, menuPath = "Plugins>GrooveJ")
public class GrooveJ implements Command, ActionListener{
	private static final Color linedColour = new Color(0, 255, 255, 64);
	private static final Color lineiColour = new Color(255, 255, 0, 64);
	private static final Dimension spinSize = new Dimension(50, 20);
	
	private ImagePlus imp, proj, levelImp, vesselImp;
	private Calibration cal;
	private Overlay ol;
	private int W,H,Z;
	private String title, unit, Aunit;
	private Line startLine, endLine;
	private ArrayList<Line> lines;
	private ArrayList<Vessel> vessels;
	
	private JFrame gui;
	private CardLayout card;
	private JFormattedTextField rdField, riField, corrField, slopeField;
	private JSpinner tolSpinner, threshSpinner, minASpinner, maxCircSpinner, lagSpinner, zThreshSpinner, depthkSpinner;
	
	private int rd = (int) Prefs.get("Groovy.rd", 50);
	private int ri = (int) Prefs.get("Groovy.ri", 25);
	private double corr = Prefs.get("Groovy.corr", 0.2);
	private int tolerance = (int) Prefs.get("Groovy.tolerance", 20);
	private int threshold = (int) Prefs.get("Groovy.threshold", 128);
	private double slopeFactor = Prefs.get("Groovy.slopeFactor", 0.3d);
	private double depthk = Prefs.get("Groovy.depthk", 0.5d);
	
    private int lag = (int) Prefs.get("Groovy.lag", 50);
    private double threshZ = Prefs.get("Groovy.threshZ", 0.5d);

	private static double downRad = 3d*Math.PI/2d;
	private static double arrowW = XYPointerAnnotation.DEFAULT_ARROW_WIDTH*1.5d;
	
	public static void plot( XYSeriesCollection col, String title, String xLabel, String yLabel, ArrayList<XYAnnotation> annotations){
		BasicStroke stroke = new BasicStroke(1f);
		
		JFreeChart chart = ChartFactory.createXYLineChart(title, xLabel, yLabel, col, PlotOrientation.VERTICAL, false, true, false);
		XYPlot plot = chart.getXYPlot();
        plot.setDomainCrosshairVisible(false);
        plot.setRangeCrosshairVisible(false);

        XYItemRenderer render = plot.getRenderer();
        for(int r=0;r<col.getSeriesCount();r++){
        	Series series = col.getSeries(r);
        	if(((String)series.getKey()).matches(".*processed intensity")){
        		render.setSeriesPaint(r, Color.RED);
        	}
        	else if(((String)series.getKey()).matches(".*intensity")){
        		render.setSeriesPaint(r, Color.ORANGE);
        	}
        	else if(((String)series.getKey()).matches(".*processed depth")){
        		render.setSeriesPaint(r, Color.GREEN);
        	}
        	else if(((String)series.getKey()).matches(".*depth")){
        		render.setSeriesPaint(r, Color.BLACK);
        	}
        	else if(((String)series.getKey()).matches("response")){
        		render.setSeriesPaint(r, Color.BLUE);
        	}
        	render.setSeriesStroke(r, stroke);
        	//render.setSeriesShape(r, shape );
        }
        
        for(XYAnnotation ann : annotations){
        	plot.addAnnotation(ann);
        }
        
        JFrame frame = new JFrame();
        frame.add(new ChartPanel(chart));
        frame.pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		frame.setLocation(screen.width/10, screen.height/10);
		int size = Math.min(screen.width, screen.height);
		frame.setSize( new Dimension(size-size/5, size-size/5) );
		frame.setVisible(true);
		frame.toFront();
	}
	
	public void run() {
		imp = WindowManager.getCurrentImage();
		title = imp.getTitle();
		W = imp.getWidth();
		H = imp.getHeight();
		Z = imp.getNSlices();
		cal = imp.getCalibration();
		
		 //set calibration in µm for 5x objective if still perkin elmered up
		if(cal.pixelWidth==1) cal.pixelWidth = 2.3919;
		if(cal.pixelHeight==1) cal.pixelHeight = 2.3919;
		if(cal.pixelDepth==1) cal.pixelDepth = 20.0;
		if(cal.getUnit().matches("[Pp]ixel")) cal.setUnit("\u00b5m");
		imp.setCalibration(cal);
		
		unit = " ("+cal.getUnit()+")";
		Aunit = " ("+cal.getUnit()+"\u00b2)";
		
		gui = new JFrame("GrooveJ");
		
		card = new CardLayout();
		gui.setLayout(card);
		gui.setIconImage(Toolkit.getDefaultToolkit().getImage(getClass().getResource("logo_icon.gif")));
		gui.setAlwaysOnTop(true);
		
		JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		
		
		JPanel fieldPanel = new JPanel();
		fieldPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.GRAY), "Profiles"));
		fieldPanel.add(new JLabel("Line Widths:  depth"));
		Format formatW = new DecimalFormat("###0.0#");
		rdField = new JFormattedTextField(formatW);
		rdField.setColumns(4);
		rdField.setBorder(BorderFactory.createLineBorder(linedColour,2));
		rdField.setValue(rd*2*cal.pixelWidth);
		fieldPanel.add(rdField);
		fieldPanel.add(new JLabel("\u00b5m,"));
		
		fieldPanel.add(new JLabel("intensity"));
		riField = new JFormattedTextField(formatW);
		riField.setColumns(4);
		riField.setBorder(BorderFactory.createLineBorder(lineiColour,2));
		riField.setValue(ri*2*cal.pixelWidth);
		fieldPanel.add(riField);
		fieldPanel.add(new JLabel("\u00b5m"));
		fieldPanel.add(Box.createHorizontalStrut(5));
		
		Format dFormat = new DecimalFormat("0.0#");
		corrField = new JFormattedTextField(dFormat);
		corrField.setColumns(2);
		corrField.setValue(corr);
		fieldPanel.add(new JLabel("Curvature:"));
		fieldPanel.add(corrField);
		
		mainPanel.add(fieldPanel);
		
		
		JPanel groovePanel = new JPanel();
		groovePanel.setLayout(new BoxLayout(groovePanel, BoxLayout.Y_AXIS));
		groovePanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.GRAY), "Groove Detection"));
		
		
		JPanel gp1 = new JPanel();
		
		SpinnerModel lagModel = new SpinnerNumberModel(lag*cal.pixelWidth, 0.0, 500.0, 10.0);
		lagSpinner = new JSpinner(lagModel);
		lagSpinner.setPreferredSize(spinSize);
		gp1.add(new JLabel("Rolling Z Window (\u00b5m):"));
		gp1.add(lagSpinner);
		gp1.add(Box.createHorizontalStrut(5));
		
		SpinnerModel threshModel = new SpinnerNumberModel(threshZ, 0.1, 10.0, 0.1);
		zThreshSpinner = new JSpinner(threshModel);
		zThreshSpinner.setPreferredSize(spinSize);
		gp1.add(new JLabel("Z Threshold:"));
		gp1.add(zThreshSpinner);
		gp1.add(Box.createHorizontalStrut(5));
		
		groovePanel.add(gp1);
		
		JPanel gp2 = new JPanel();
		
		slopeField = new JFormattedTextField(dFormat);
		slopeField.setColumns(2);
		slopeField.setValue(slopeFactor);
		gp2.add(new JLabel("Slope Factor:"));
		gp2.add(slopeField);
		gp2.add(Box.createHorizontalStrut(5));
		
		SpinnerModel depthkModel = new SpinnerNumberModel(depthk, 0.01, 1.0, 0.01);
		depthkSpinner = new JSpinner(depthkModel);
		depthkSpinner.setPreferredSize(spinSize);
		gp2.add(new JLabel("Depth Weighting Factor:"));
		gp2.add(depthkSpinner);
		
		groovePanel.add(gp2);
		
		mainPanel.add(groovePanel);
		
		
		JPanel peakPanel = new JPanel();
		peakPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.GRAY), "Vessel Detection"));
		
		SpinnerModel sm1 = new SpinnerNumberModel(tolerance, 1, 255, 1);
		tolSpinner = new JSpinner(sm1);
		tolSpinner.setPreferredSize(spinSize);
		peakPanel.add(new JLabel("Peak Tolerance:"));
		peakPanel.add(tolSpinner);
		peakPanel.add(Box.createHorizontalStrut(5));
		
		SpinnerModel sm2 = new SpinnerNumberModel(threshold, 1, 255, 1);
		threshSpinner = new JSpinner(sm2);
		threshSpinner.setPreferredSize(spinSize);
		peakPanel.add(new JLabel("Peak Threshold:"));
		peakPanel.add(threshSpinner);
		
		mainPanel.add(peakPanel);
		
		
		JPanel filterPanel = new JPanel();
		filterPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.GRAY), "Vessel Filter"));
		SpinnerModel minAModel = new SpinnerNumberModel(50, 0, 1000, 1);
		minASpinner = new JSpinner(minAModel);
		minASpinner.setPreferredSize(spinSize);
		filterPanel.add(new JLabel("Min. Area (\u00b5m\u00b2):"));
		filterPanel.add(minASpinner);
		filterPanel.add(Box.createHorizontalStrut(10));
		SpinnerModel maxCircModel = new SpinnerNumberModel(0.9, 0, 1.0, 0.05);
		maxCircSpinner = new JSpinner(maxCircModel);
		maxCircSpinner.setPreferredSize(spinSize);
		filterPanel.add(new JLabel("Max. Circularity:"));
		filterPanel.add(maxCircSpinner);
		mainPanel.add(filterPanel);
		
		
		JPanel linePanel = new JPanel();
		linePanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.GRAY), "Set Profile Lines"));
		JButton start = new JButton("Set Line");
		start.setActionCommand("start");
		start.addActionListener(this);
		linePanel.add(start);
		JButton end = new JButton("Set interpolated line end");
		end.setActionCommand("end");
		end.addActionListener(this);
		linePanel.add(end);
		JButton reset = new JButton("reset");
		reset.addActionListener(this);
		linePanel.add(reset);
		mainPanel.add(linePanel);
		
		
		JPanel bottomPanel = new JPanel();
		JButton run = new JButton("Run");
		run.addActionListener(this);
		bottomPanel.add(run);
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(this);
		bottomPanel.add(cancel);
		mainPanel.add(bottomPanel);
		
		
		gui.add(mainPanel);
		card.addLayoutComponent(mainPanel, "mainPanel");
		
		JPanel workPanel = new JPanel(new BorderLayout());
		workPanel.setBackground(Color.WHITE);
		java.net.URL url = this.getClass().getResource("groovyWorking.gif");
		ImageIcon img = new ImageIcon(Toolkit.getDefaultToolkit().createImage(url));
		JLabel workLabel = new JLabel(img);
		workLabel.setFont(new Font("Sans-serif",Font.PLAIN,20));
		workPanel.add(workLabel,BorderLayout.CENTER);
		
		gui.add(workPanel);
		card.addLayoutComponent(workPanel, "workPanel");
		
		gui.pack();
		gui.setLocationRelativeTo(null);
		gui.setVisible(true);
		
	}
	
	private void calculateLines(){
		if(startLine==null){
			JOptionPane.showMessageDialog(null, "No Line", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		lines = new ArrayList<Line>();
		
		lines.add(startLine);
		if(endLine!=null){
			double dist1 = Math.sqrt( ((endLine.x1-startLine.x1)*(endLine.x1-startLine.x1))+((endLine.y1-startLine.y1)*(endLine.y1-startLine.y1)) );
			double dist2 = Math.sqrt( ((endLine.x2-startLine.x2)*(endLine.x2-startLine.x2))+((endLine.y2-startLine.y2)*(endLine.y2-startLine.y2)) );
			int n = (int) ((Math.min(dist1, dist2)/(double)((Math.max(rd,ri)*2)+1))-1);
			
			int dx1 = (int) ((endLine.x1-startLine.x1)/(double)n);
			int dy1 = (int) ((endLine.y1-startLine.y1)/(double)n);
			int dx2 = (int) ((endLine.x2-startLine.x2)/(double)n);
			int dy2 = (int) ((endLine.y2-startLine.y2)/(double)n);
			for(int i=1;i<=n;i++){
				Line line = new Line( startLine.x1+(dx1*i), startLine.y1+(dy1*i), startLine.x2+(dx2*i), startLine.y2+(dy2*i) );
				lines.add(line);
			}
		}
		
		if(startLine!=null){
			for(Line line:lines){
				line.setStrokeWidth(2*rd);		//show depth line width
				line.setStrokeColor(linedColour);
				ol.add(line);
				Line copy = (Line) line.clone();	//copy for intensity line width
				copy.setStrokeWidth(2*ri);
				line.setStrokeColor(lineiColour);
				ol.add(copy);
			}
		}
		
	}
	
	private Line getLineRoi(){
		if(imp.getRoi()==null){
			JOptionPane.showMessageDialog(null, "No Roi", "Error", JOptionPane.ERROR_MESSAGE);
			return null;
		}
		Roi roi = imp.getRoi();
		if(!roi.isLine()){
			JOptionPane.showMessageDialog(null, "Roi is not a Line", "Error", JOptionPane.ERROR_MESSAGE);
			return null;
		}
		imp.killRoi(); //necessary to reset Roi to avoid same object being returned for start and end
		return (Line)roi;
	}
	
	private void measureLines(){
		proj.setPosition(1,1,1);
		ResultsTable rt = new ResultsTable();
		for(int k=0;k<lines.size();k++){
			Line line = lines.get(k);
			XYSeries intensitySeries = new XYSeries(k+" intensity");
			XYSeries procIntensitySeries = new XYSeries(k+" processed intensity");
			XYSeries depthSeries = new XYSeries(k+" depth");
			XYSeries procDepthSeries = new XYSeries(k+" processed depth");
			imp.setRoi(line);
			vesselImp.setRoi(line);
			levelImp.setRoi(line);
			int dlineWidth = 2*rd;
			int ilineWidth = 2*ri;
			ImageProcessor projStraight = (new Straightener()).straighten(vesselImp, (Line)line, ilineWidth); //intensity
	        ImageProcessor levelStraight = (new Straightener()).straighten(levelImp, (Line)line, dlineWidth); //depth
	        int width = projStraight.getWidth();
	        int height = projStraight.getHeight();
	        double[] projProfile = new double[width];
	        double[] depthProfile = new double[width];
	        double[] aLine, bLine;
	        projStraight.setInterpolate(false);
	        levelStraight.setInterpolate(false);
	        for (int y=0; y<height; y++) {
	            aLine = projStraight.getLine(0, y, width-1, y);
	            bLine = levelStraight.getLine(0, y, width-1, y);
	            for (int i=0; i<width; i++){
	                projProfile[i] += aLine[i];
	            	depthProfile[i] += bLine[i]*cal.pixelDepth;
	            }
	        }
	        
	        //average across line width
	        for (int i=0; i<width; i++){
	            projProfile[i] /= height;
	            depthProfile[i] /= height;
	            rt.setValue("Position "+k+unit, i, i*cal.pixelWidth);
	            rt.setValue("Groove", i, "");
	        	rt.setValue("C1 Intensity "+k, i, projProfile[i]);
	        	rt.setValue("Peak Distance to Groove Center"+unit, i, "");
	        	rt.setValue("Depth "+k+unit, i, depthProfile[i]);
	        }
	        
	        //local smooth
	        int range = 5;
	        double[] smoothedIntensity = new double[projProfile.length];
	        double[] smoothedDepth = new double[depthProfile.length];
	        double maxIntensity = -1d;
	        for (int i=0; i<projProfile.length; i++){
	        	smoothedIntensity[i] = 0;
	        	smoothedDepth[i] = 0;
	        	for(int s=-range;s<=range;s++){
	        		int get = Math.max(0, Math.min(projProfile.length-1, i+s));
	        		smoothedIntensity[i] += projProfile[get];
	        	}
	        	smoothedIntensity[i] = smoothedIntensity[i]/((range*2)+1);
	        	procIntensitySeries.add(i*cal.pixelWidth, smoothedIntensity[i]);	//plot smoothed intensity
	        	//intensitySeries.add(i*cal.pixelWidth, projProfile[i]);				//plot raw intensity
	        	maxIntensity = Math.max(maxIntensity, smoothedIntensity[i]);
	        	
	        	for(int s=-range*10;s<=range*10;s++){
	        		int get = Math.max(0, Math.min(projProfile.length-1, i+s));
	        		smoothedDepth[i] += depthProfile[get];
	        	}
	        	smoothedDepth[i] = smoothedDepth[i]/((range*10*2)+1);
	        	rt.setValue("Smoothed Depth "+k+unit, i, smoothedDepth[i]);
	        }
	        
	        //scale depth to correct for curvature
	        double[] curvef = new double[smoothedDepth.length];
	        double mid = curvef.length/2d;
	        for (int i=0; i<curvef.length; i++){
	        	curvef[i] = 1d/Math.cos( ((i-mid)/mid)*corr );
	        }
	        double maxDepth = -1d;
	        double meanDepth = 0d;
	        double[] corrDepth = new double[smoothedDepth.length];
	        for (int i=0; i<smoothedDepth.length; i++){
	        	corrDepth[i] = smoothedDepth[i]*curvef[i];
	        	procDepthSeries.add(i*cal.pixelWidth, corrDepth[i]);  //plot smoothed and corrected depth
	        	depthSeries.add(i*cal.pixelWidth, depthProfile[i]); //plot raw depth
	        	meanDepth += corrDepth[i];
	        	maxDepth = Math.max(maxDepth, corrDepth[i]);
	        	rt.setValue("Curve Corrected Depth "+k+unit, i, corrDepth[i]);
	        }
	        meanDepth /= corrDepth.length;
	        
	        int[] michaelMaxima = MaximumFinder.findMaxima(smoothedIntensity, tolerance, true); //peaks from smoothed profile
	        
	        ArrayList<Integer> thresholdedMaxima = new ArrayList<Integer>();
	        for(int i=0;i<michaelMaxima.length;i++){
	        	if(smoothedIntensity[michaelMaxima[i]]>=threshold){
	        		thresholdedMaxima.add(michaelMaxima[i]);
	        	}
	        }
	        
	        int[] intensityMaxima = new int[thresholdedMaxima.size()];
	        for(int i=0;i<intensityMaxima.length;i++){
	        	intensityMaxima[i] = thresholdedMaxima.get(i);
	        }
	        
	        ArrayList<XYAnnotation> peaks = new ArrayList<XYAnnotation>();
	        ArrayList<XYAnnotation> grooves = new ArrayList<XYAnnotation>();
	        Color grooveColour = new Color(128, 0, 0, 64);
	        
	        int[] grooveResponse = GrooveFinder.find(corrDepth, lag, threshZ);	//find grooves from smoothed and corrected depth
	        ArrayList<Double[]> grooveRanges = new ArrayList<Double[]>();
	        int start = -1;
	        int g = 0;
	        for(int a=0;a<grooveResponse.length;a++) {
	        	if(grooveResponse[a]==1){
	        		int inc0 = a;
		        	while(a<grooveResponse.length&&grooveResponse[a]==1){
		        		a++;
		        	}
		        	int incW = a-inc0;
		        	if(incW<rd) continue;	//ignore if the increasing depth response is narrower than the line width
		        	start = (int) (inc0+(incW*slopeFactor));
	        	}
	        	if(a>grooveResponse.length-1) break;
	        	if(start>=0&&grooveResponse[a]==-1){
	        		int dec0 = a;
		        	while(a<grooveResponse.length&&grooveResponse[a]==-1){
		        		a++;
		        	}
		        	int decW = a-dec0;
		        	if(decW<rd) continue;	//ignore if the decreasing depth response is narrower than the line width
		        	int end = (int) (dec0+(decW*(1d-slopeFactor)));
		        	if(start>0&&end<grooveResponse.length-1){ //exclude edge grooves
		        		g += end-start;
			        	XYBoxAnnotation boxG = new XYBoxAnnotation(start*cal.pixelWidth, 0, end*cal.pixelWidth, maxDepth*1.1, null, null, grooveColour);
	        			grooves.add(boxG);
	        			XYBoxAnnotation boxP = new XYBoxAnnotation(start*cal.pixelWidth, 0, end*cal.pixelWidth, maxIntensity*1.1, null, null, grooveColour);
	        			peaks.add(boxP);
	        			grooveRanges.add( new Double[]{start*cal.pixelWidth, end*cal.pixelWidth} );
		        	}
        			start = -1;
	        	}
	        }
	        IJ.log( "Groove percentage = "+IJ.d2s((g/(float)grooveResponse.length)*100f, 2)+" %" );
	        
	        double inSum = 0; int inn = 0;
	        double outSum = 0; int outn = 0;
	        for(int i=0;i<rt.getCounter();i++){
	        	double pos = rt.getValue("Position "+k+unit, i);
	        	boolean in = false;
	        	for(int gi=0;gi<grooveRanges.size();gi++){
	        		Double[] groove = grooveRanges.get(gi);
	        		if(pos>=groove[0]&&pos<=groove[1]){
	        			rt.setValue("Groove", i, ""+gi);
	        			inSum += rt.getValue("C1 Intensity "+k, i);
	        			in = true;
	        			inn++;
	        			break;
	        		}
	        	}
	        	if(!in){ 
	        		outSum += rt.getValue("C1 Intensity "+k, i);
	        		outn++;
	        	}
	        }
	        
	        int nIn = 0;
	        int nOut = 0;
	        for(int p:intensityMaxima){
	        	boolean in = false;
	        	for(Double[] groove:grooveRanges){
		        	if(p*cal.pixelWidth>groove[0]&&p*cal.pixelWidth<groove[1]){
		        		in = true;
		        		double midDist = Math.abs( (p*cal.pixelWidth)-((groove[0]+groove[1])/2d) );
		        		rt.setValue("Peak Distance to Groove Center"+unit, p, midDist);
		        		continue;
		        	}
	        	}
	        	if(in) nIn++;
	        	else nOut++;
	        	
	        	XYPointerAnnotation peakLineP = new XYPointerAnnotation("", p*cal.pixelWidth, smoothedIntensity[p], downRad);
	        	peakLineP.setArrowWidth(arrowW);
	        	peaks.add(peakLineP);
	        	XYPointerAnnotation peakLineG = new XYPointerAnnotation("", p*cal.pixelWidth, corrDepth[p], downRad);
	        	peakLineG.setArrowWidth(arrowW);
	        	grooves.add(peakLineG);
	        }
	        
	        XYSeries response = new XYSeries("response");
	        for(int a=0;a<grooveResponse.length;a++) {
	        	response.add(a*cal.pixelWidth, meanDepth+(grooveResponse[a]*100d));
	        }
	        
	        XYSeriesCollection intensityCollection = new XYSeriesCollection();
			XYSeriesCollection depthCollection = new XYSeriesCollection();
			
	        intensityCollection.addSeries(intensitySeries);
	        intensityCollection.addSeries(procIntensitySeries);
	        depthCollection.addSeries(depthSeries);
	        depthCollection.addSeries(procDepthSeries);
	        depthCollection.addSeries(response);
	        
	        
	        plot(intensityCollection, "Line "+k+" C1 Intensity", "Distance"+unit, "C1 Intensity (AU)", peaks);
			plot(depthCollection, "Line "+k+" Depth", "Distance"+unit, "Depth"+unit, grooves);
			IJ.log(title+" line "+k+" : "+grooveRanges.size()+" grooves with "+nIn+" vessels in grooves, "+nOut+" outside grooves, grooviness = "+(nIn/(float)(nIn+nOut)));
			IJ.log("-    C1 mean in grooves = "+IJ.d2s(inSum/inn,2)+", C1 mean outside grooves = "+IJ.d2s(outSum/outn,2)+", groove integrated signal ratio = "+IJ.d2s(inSum/outSum,2));
		}
		rt.show("Groovy Line Profiles");
		
	}
	
	private void getMeasureImages(){
		
		ZProjector zp = new ZProjector();
		zp.setMethod(ZProjector.MAX_METHOD);
		zp.setImage(imp);
		zp.setStartSlice(1);
		zp.setStopSlice(Z);
		zp.doHyperStackProjection(true);
		proj = zp.getProjection();

		vesselImp = getVesselMap(proj, null);
		
		IJ.run(vesselImp, "Create Selection", "");
		if(vesselImp.getStatistics().mean==0) IJ.run(vesselImp, "Make Inverse", "");
		Roi vesselRoi = vesselImp.getRoi();
		Roi[] vesselRois = new ShapeRoi(vesselRoi).getRois();
		vesselImp.killRoi();
		ImageProcessor vip = vesselImp.getProcessor();
		vip.setColor(0);
		double minA = ((Number) minASpinner.getValue()).doubleValue()/cal.pixelWidth;
		for(Roi vr:vesselRois){
			if(vr.getStatistics().area<minA){
				vip.fill(vr);
			}
		}
		
		ByteProcessor levelip = new ByteProcessor(W, H);
		Duplicator dup = new Duplicator();
		ImagePlus levelStack = dup.run(imp, 2,2, 1,Z, 1,1);
		levelStack.setCalibration(cal);
		IJ.run(levelStack, "Gaussian Blur...", "sigma=8 scaled stack");
		ImageStack stack = levelStack.getStack();
		
		depthk = ((Number) depthkSpinner.getValue()).doubleValue();
		for(int y=0;y<H;y++){
			for(int x=0;x<W;x++){
				float max = 0;
				int hi = 0;
				for(int z=0;z<Z;z++){
					ImageProcessor zip = stack.getProcessor(z+1);
					float value = zip.getPixelValue(x,y);
					//float score = (float) (value*Math.pow(z/(float)Z, depthk));
					if(value>max){
						max = value;
						hi = z;
					}
				}
				levelip.set(x,y,hi);
			}
		}
		
		levelImp = new ImagePlus("", levelip);
		levelImp.setCalibration(cal);
		levelStack.close();
	}
	
	//Isabella's pipeline to make binary vessel map
	private ImagePlus getVesselMap(ImagePlus proj, Roi area){
		Duplicator dup = new Duplicator();
		ImagePlus vesselMap = dup.run(proj, 1,1, 1,1, 1,1);
		if(area!=null){
			vesselMap.setRoi(area);
			IJ.setBackgroundColor(0, 0, 0);
			IJ.run(vesselMap, "Clear Outside", "");
			vesselMap.killRoi();
		}
		IJ.run(vesselMap, "Subtract Background...", "rolling=50");
		IJ.run(vesselMap, "Enhance Contrast...", "saturated=0.3");
		IJ.run(vesselMap, "8-bit", "");
		final Auto_Local_Threshold at = new Auto_Local_Threshold();
		at.exec(vesselMap, "Phansalkar", 5, 0d, 0d, true);
		return vesselMap;
	}
	
	//map ceramic area and return as Roi
	private Roi getCeramicArea(){
		Duplicator dup = new Duplicator();
		GaussianBlur carl = new GaussianBlur();
		double sigma = 40d; //µm
		ImagePlus map = dup.run(proj, 2, 2, 1, 1, 1, 1);	//C2 DAPI
		carl.blurGaussian(map.getProcessor(), sigma/cal.pixelWidth);
		IJ.setAutoThreshold(map, "Li dark");
		Prefs.blackBackground = true;
		IJ.run(map, "Convert to Mask", "");
		IJ.run(map, "Create Selection", "");
		if(map.getRoi()==null){
			IJ.log("No ceramic area found.");
			map.show("ceramic");
		}
		if(map.getStatistics().mean==0) IJ.run(map, "Make Inverse", "");
		Roi roi = map.getRoi();
		Roi[] rois = new ShapeRoi(roi).getRois();
		double maxA = Double.NEGATIVE_INFINITY;
		int maxi = -1;
		for(int r=0;r<rois.length;r++){
			map.setRoi(rois[r]);
			double area = map.getStatistics().area;
			if(area>maxA){
				maxA = area;
				maxi = r;
			}
			map.killRoi();
		}
		map.close();
		Roi ceramRoi = new ShapeRoi( new ShapeRoi(rois[maxi]).getConvexHull() );
		return ceramRoi;
	}
	
	private void measureCells(){
		Roi ceramRoi = getCeramicArea();
		
		ImagePlus vesselMap = getVesselMap(proj, ceramRoi);
		
		double minA = ((Number) minASpinner.getValue()).doubleValue();
		double maxCirc = ((Number) maxCircSpinner.getValue()).doubleValue();
		
		double maxDepth = levelImp.getStatistics().max;
		IJ.run(vesselMap, "Create Selection", "");
		if(vesselMap.getStatistics().mean==0) IJ.run(vesselMap, "Make Inverse", "");
		Roi[] vRois = new ShapeRoi(vesselMap.getRoi()).getRois();
		vessels = new ArrayList<Vessel>();
		ResultsTable vrt = new ResultsTable();
		for(int v=0;v<vRois.length;v++){
			Roi ves = vRois[v];
			levelImp.setRoi(ves);
			ImageStatistics stats = levelImp.getStatistics();
			double perim = ves.getLength()*cal.pixelWidth;
			double circ = 4*Math.PI*(stats.area/(perim*perim));
			if(stats.area<minA||(minA>0&&stats.area>(minA*1000))||circ>maxCirc){
				continue;
			}
			Vessel vessel = new Vessel(ves, stats, maxDepth);
			vessels.add(vessel);
			ol.add(vessel.roi);
			int row = vrt.getCounter();
			vrt.setValue("Depth"+unit, row, stats.mean*cal.pixelDepth);
			vrt.setValue("Area"+Aunit, row, stats.area);
			double[] feret = ves.getFeretValues();
			vrt.setValue("Aspect Ratio", row, feret[2]/feret[0]);
			vrt.setValue("Circularity", row, circ);
			double radius  = Math.sqrt(stats.area/Math.PI);
			double circlePerim = 2d*Math.PI*radius;	//perimeter of a circle with the same area
			double branchedness = Math.max( 0d, (perim/circlePerim)-1 );
			vrt.setValue("Branchedness", row, branchedness);
			
			levelImp.setRoi( new ShapeRoi(ves.getConvexHull()) );
			ImageStatistics hullStats = levelImp.getStatistics();
			double sol = stats.area/hullStats.area;
			vrt.setValue("Solidity", row, sol);
		}
		vesselMap.close();
		vrt.show("Groovy Vessels");
	}
	
	public void actionPerformed(ActionEvent ae){
		try{
		String e = ae.getActionCommand();
		
		rd = (int) Math.round( (Double.valueOf(rdField.getText())/cal.pixelWidth)/2d );	//get depth and intensity line "radius" from width field
		ri = (int) Math.round( (Double.valueOf(riField.getText())/cal.pixelWidth)/2d );
		corr = Double.valueOf(corrField.getText());
		lag = (int) Math.round( ((double)lagSpinner.getValue())/cal.pixelWidth);
		threshZ = (double) zThreshSpinner.getValue();
		slopeFactor = Double.valueOf(slopeField.getText());
		tolerance = (int) tolSpinner.getValue();
		threshold = (int) threshSpinner.getValue();
		
		Prefs.set("Groovy.rd", rd);
		Prefs.set("Groovy.ri", ri);
		Prefs.set("Groovy.corr", corr);
		Prefs.set("Groovy.slopeFactor", slopeFactor);
		Prefs.set("Groovy.tolerance", tolerance);
		Prefs.set("Groovy.threshold", threshold);
		Prefs.set("Groovy.depthk", depthk);
		
		ol = new Overlay();
		if(e.equals("start")){
			startLine = getLineRoi();
			calculateLines();
			imp.setOverlay(ol);
		}
		else if(e.equals("end")){
			endLine = getLineRoi();
			calculateLines();
			imp.setOverlay(ol);
		}
		else if(e.equals("reset")){
			startLine = null;
			endLine = null;
			lines = null;
			imp.setOverlay(null);
		}
		else if(e.equals("Cancel")){
			gui.dispose();
		}
		else if(e.equals("Run")){
			
			SwingWorker<Object, Object> worker = new SwingWorker<Object, Object>(){
				public Object doInBackground(){
					try{
						card.show(gui.getContentPane(), "workPanel");
						calculateLines();
						getMeasureImages();
						measureCells();
						measureLines();
						card.show(gui.getContentPane(), "mainPanel");
					}catch(Exception e){System.out.print(e.toString()+"\n~~~~~\n"+Arrays.toString(e.getStackTrace()).replace(",","\n"));}
					return null;
				}
			};
			worker.execute();

			imp.setOverlay(ol);
		}
		}catch(Exception e){System.out.print(e.toString()+"\n~~~~~\n"+Arrays.toString(e.getStackTrace()).replace(",","\n"));}
		finally{
			imp.killRoi();
			if(proj!=null) proj.close();
			if(levelImp!=null) levelImp.close();
			if(vesselImp!=null) vesselImp.close();
		}
	}
	
	public static void main(String[] arg){
		final ij.ImageJ ij = new ij.ImageJ();
		
		ImagePlus img = new ImagePlus("E:\\Isabella\\Jan2018 Opera Data\\Day7_A1_1secExposure__2017-12-19T12_46_12-Measurement 1_Combined Stacks.tif");
		final ImagePlus image = HyperStackConverter.toHyperStack(img, 2, 40, 1);
		//ImagePlus img = new ImagePlus("E:\\Isabella\\Jan2018 Opera Data\\Day7_A6_1secExposure__2017-12-19T14_12_38-Measurement 2.tif");
		//final ImagePlus image = HyperStackConverter.toHyperStack(img, 2, 37, 1);
		//ImagePlus img = new ImagePlus("E:\\Isabella\\error_20180306\\MAX_row-1 column-1 mosaic.tif");
		//final ImagePlus image = img;
		
		image.show();
		ij.addWindowListener(new WindowAdapter(){
			public void windowClosing(WindowEvent we){
				System.exit(1);
			}
		});
		
		new GrooveJ().run();
	}

	
}
