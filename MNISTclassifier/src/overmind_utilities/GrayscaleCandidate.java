package overmind_utilities;
import java.io.Serializable;

public class GrayscaleCandidate implements Serializable {
	public float[] grayscalePixels;
	public int label;
	
	public GrayscaleCandidate(float[] grayscalePixels, int label) {
		this.grayscalePixels = new float[grayscalePixels.length];
        System.arraycopy(grayscalePixels, 0, this.grayscalePixels, 0, grayscalePixels.length);
        this.label = label;
	}
}
