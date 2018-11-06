package overmind_utilities;
import java.io.Serializable;
import java.util.ArrayList;
import overmind_server.*;

/**
 * Class that creates a file which stores the weights of all the nodes used by the program.
 * @author rodolfo
 *
 */

public class WeightsFile implements Serializable {
	public NodeWeights[] excNodesWeights;
	public NodeWeights[] inhNodesWeights;
	
	public WeightsFile(ArrayList<Node> excNodes, ArrayList<Node> inhNodes) {
		excNodesWeights = new NodeWeights[excNodes.size()];
		inhNodesWeights = new NodeWeights[inhNodes.size()];
		
		for (int i = 0; i < excNodes.size(); i++) {
			Node excNode = excNodes.get(i);
						
			excNodesWeights[i] = new NodeWeights(
					weightsFloatToByte(VirtualLayerManager.weightsTable.get(excNode.id)),
					excNode.terminal.numOfSynapses, 
					excNode.terminal.numOfNeurons);
		}
		
		for (int i = 0; i < inhNodes.size(); i++) {
			Node inhNode = inhNodes.get(i);
			int arrayLength = (inhNode.originalNumOfSynapses - inhNode.terminal.numOfDendrites) * 
					inhNode.terminal.numOfNeurons;
			
			inhNodesWeights[i] = new NodeWeights(
					weightsFloatToByte(VirtualLayerManager.weightsTable.get(inhNode.id)),
					inhNode.terminal.numOfSynapses, 
					inhNode.terminal.numOfNeurons);
		}
	}
	
	/**
	 * Convert an array of float into bytes, assuming that each number can be represented with 8 bit only
	 * is an integer between 0 and 255.	
	 * @param floatWeights The array of floats to be converted.
	 * @param arrayLength The lengths of the array.
	 * @return An array containing the weights cast into bytes.
	 */
	
	private byte[] weightsFloatToByte(float[] floatWeights) {
		byte[] byteWeights = new byte[floatWeights.length];
		for (int i = 0; i < floatWeights.length; i++) {
			byteWeights[i] = (byte)(floatWeights[i] / UtilConst.MIN_WEIGHT);
		}
		
		return byteWeights;
	}

	/**
	 * Private class that stores all the information necessary to assign the 
	 * right weights to the right node when the weights file is retrieved from storage
	 * memory.
	 * @author rodolfo
	 *
	 */
	
	public class NodeWeights implements Serializable {
		public byte[] weights;
		public int numOfSynapses, numOfNeurons;
		
		NodeWeights(byte[] weights, int numOfSynapses, int numOfNeurons) {
			this.weights = weights;
			this.numOfSynapses = numOfSynapses;
			this.numOfNeurons = numOfNeurons;
		}
		
		@Override
	    public boolean equals(Object obj) {			       
			if (obj.getClass().equals(Node.class)) {
				Node compare = (Node) obj;
				return compare.terminal.numOfNeurons == numOfNeurons & 
						compare.terminal.numOfSynapses == numOfSynapses;
			}   	
			return false;
	    }
	}	
}
