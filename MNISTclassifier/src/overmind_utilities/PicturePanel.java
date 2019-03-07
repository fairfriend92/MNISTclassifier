package overmind_utilities;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

public class PicturePanel extends JPanel {
	
	private float[] pixels;
	private int width = 0, height = 0;
	
	public PicturePanel (float[] pixels, int width, int height, int scale) {
		setBorder(BorderFactory.createLineBorder(Color.black));
		setBackground(Color.white);
		
		updatePicturePanel(pixels, width, height, scale);
	}
	
	public void updatePicturePanel(float[] pixels, int width, int height, int scale) {
		this.width = scale * width;
		this.height = scale * height;		
		
		this.pixels = new float[scale * scale * pixels.length];
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				paintScaledPixel(x, y, scale, pixels[y * width + x]);
			}
		}
	}
		
	private void paintScaledPixel(int originalX, int originalY, int scale, float value) {
		for (int x = 0; x < scale; x++) {
			for (int y = 0; y < scale; y++) {
				pixels[(originalY * scale + y) * width + originalX * scale + x] = value;
			}
		}
	}
	
	@Override
	public Dimension getPreferredSize() {
	        return new Dimension(width, height);
	}
	 
	@Override
	public void paintComponent(Graphics g) {
	       super.paintComponent(g); 
	       
	       // Draw black and white picture from grayscale data. 
	       for (int x = 0; x < width; x++) {
	    	   for (int y = 0; y < height; y++) {
	    		   float colorValue = (255.0f - pixels[y * width + x]) / 255.0f;
	    		   g.setColor(new Color(colorValue, colorValue, colorValue));
	    		   g.drawLine(x, y, x, y);
	    	   }
	       }
	}
}
