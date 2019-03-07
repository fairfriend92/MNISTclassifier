package overmind_utilities;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import com.example.overmind.*;
import javax.swing.DefaultListModel;
import overmind_server.Node;
import overmind_server.VirtualLayerManager;

public class NodesManager {
	public static ArrayList<Node> excNodes = new ArrayList<>(); // Nodes with excitatory neurons only 
	public static ArrayList<Node> inhNodes = new ArrayList<>(); // Nodes with inhibitory neurons only 

	public static DefaultListModel<String> excNodesListModel = new DefaultListModel<>();
	public static DefaultListModel<String> inhNodesListModel = new DefaultListModel<>();
	
	public static int removeAppFromConnections (Node node, Terminal app) {
		
		if (!excNodes.contains(node) | !inhNodes.contains(node)) {
			System.out.println("removeAppFromConnections: Node does not belong to the app.");
			return UtilConst.ERROR;
		} else {
			// TODO: This works just fine to delete the single postsynaptic connection but 
			// since the input terminals used different nat ports they can only be identified using their IPs
			node.terminal.presynapticTerminals.remove(app);
			
			node.terminal.postsynapticTerminals.remove(app);
			node.terminal.numOfSynapses += node.terminal.numOfNeurons;
		}
		
		return UtilConst.SUCCESS;
	}
	
	/**
	 * Reset the network to the default state before any node was selected. 
	 * This method is used whenever an error arises during either the training or the
	 * testing phase and the application must be interrupted. 
	 */
	
	public static void resetNetwork(Terminal app) {		
		for (Node excNode : excNodes) {
			int result = removeAppFromConnections(excNode, app);
			if (result == UtilConst.SUCCESS) {
				VirtualLayerManager.unsyncNodes.add(excNode);
				Future<Boolean> future = VirtualLayerManager.syncNodes();	  			        
				try {
					Boolean syncSuccessful = future.get();
					if (!syncSuccessful) {
						//Main.updateLogPanel("TCP stream interrupted", Color.RED);
						System.out.println("resetNetwork: TCP stream interrupted");
						VirtualLayerManager.removeNode(excNode, true);
					} 
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			}
			
			excNode.terminalFrame.randomSpikesRadioButton.setEnabled(true);
			excNode.terminalFrame.refreshSignalRadioButton.setEnabled(true);
			excNode.isExternallyStimulated = false; 
			
			if (!VirtualLayerManager.availableNodes.contains(excNode))
				VirtualLayerManager.availableNodes.add(excNode);			
		}
		
		for (Node inhNode : inhNodes) {
			int result = removeAppFromConnections(inhNode, app);
			if (result == UtilConst.SUCCESS) {
				VirtualLayerManager.unsyncNodes.add(inhNode);
				Future<Boolean> future = VirtualLayerManager.syncNodes();	  			        
				try {
					Boolean syncSuccessful = future.get();
					if (!syncSuccessful) {
						//Main.updateLogPanel("TCP stream interrupted", Color.RED);
						System.out.println("resetNetwork: TCP stream interrupted");
						VirtualLayerManager.removeNode(inhNode, true);
					} 
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			}
			
			inhNode.terminalFrame.randomSpikesRadioButton.setEnabled(true);
			inhNode.terminalFrame.refreshSignalRadioButton.setEnabled(true);
			inhNode.isExternallyStimulated = false; 
			
			if (!VirtualLayerManager.availableNodes.contains(inhNode))
				VirtualLayerManager.availableNodes.add(inhNode);
		}
		
		excNodes.clear();
		excNodesListModel.clear();
		excNodesListModel.addElement("No excitatory node");
		
		inhNodes.clear();
		inhNodesListModel.clear();
		inhNodesListModel.addElement("No inhibitory node");		
	}
}
