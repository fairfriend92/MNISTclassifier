package overmind_utilities;

/**
 * Class containing methods that pertain to the input made of spikes which is sent
 * to the network. 
 * @author rodolfo
 *
 */

public class SpikeInputCreator {	
	
	/**
	 * Create a spike input from a map of pixel luminance.
	 */	
	
	public  byte[] createFromLuminance(float[] grayscalePixels, boolean printValues) {
		byte[] spikeInput;
		short lengthInBytes; // How many bytes are needed to represent the spike input?
		
		// Each float is the luminance of a pixel and each pixel corresponds to a synapse
		lengthInBytes = (short)(grayscalePixels.length % 8 == 0 ? grayscalePixels.length / 8 :
			grayscalePixels.length / 8 + 1);
		
		/* 
		 * The intesity of a pixel represents the probability for a given synapse to 
		 * receive a spike. Hence, compare the intensity with a random number between 1 and 0 and,
		 * if it's greater than it, set the bit corresponding to the synapse.
		 */
		
		spikeInput = new byte[lengthInBytes];
		double randomLuminance = Math.random() * 255; // Store a random number between 0 and 255				
				
		int pixelsCounter = 0, totalSpikes = 0;
		
		// Iterate over all pixels of the image
		for (float luminance : grayscalePixels) {
			luminance = luminance > 0 ? 255 : 0;
			int byteIndex = pixelsCounter / 8;	
			
			// Set the bit corresponding to the current pixel or synapse
			if (randomLuminance < luminance) {		
				spikeInput[byteIndex] |= (1 << pixelsCounter - byteIndex * 8);	
			}
			
			pixelsCounter++;		
		}
		
		return spikeInput;
	}
}
