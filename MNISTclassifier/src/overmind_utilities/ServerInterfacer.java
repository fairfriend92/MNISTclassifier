package overmind_utilities;
import overmind_server.*;

public class ServerInterfacer extends Thread {
	public boolean shutdown = false;
	String[] args;
	
	public ServerInterfacer(String[] args) {
		this.args = args;
	}
	
	public static ApplicationInterface.RegisteredApp thisApplication = null;
	
	@Override
	public void run() {
		super.run();
		MainFrame.main(args); // Start the Overmind server
		
		thisApplication = 
				ApplicationInterface.registerApplication(AppInfo.maxRemovableNodes, AppInfo.name);
		
		while(!shutdown) {		
			ApplicationInterface.RemovedNode removedNodeObject = null;
			
			try {
				removedNodeObject = thisApplication.removedNodes.take();
			} catch (InterruptedException e) {
				System.out.println(AppInfo.name + ": serverInterfacer interrupted");
				break;
			}
			assert removedNodeObject != null;			
			
			System.out.println("A node has been removed from the network");
			
			// Enter the block if no substitute for the removed node is available 			
			if (removedNodeObject.shadowNode == null) {	
				
				/*
				 * Update the lists of excitatory  and inhibitory nodes 
				 */
				
				if (NodesManager.excNodes.contains(removedNodeObject.removedNode)) {
					NodesManager.excNodes.remove(removedNodeObject.removedNode);
					NodesManager.excNodesListModel.removeElement(removedNodeObject.removedNode.terminal.ip);					
				}			
				
				if (NodesManager.inhNodes.contains(removedNodeObject.removedNode)) {
					NodesManager.inhNodes.remove(removedNodeObject.removedNode);
					NodesManager.inhNodesListModel.removeElement(removedNodeObject.removedNode.terminal.ip);					
				}		
			}
			
			// If the exc nodes and inh nodes list are empty, put back the default text in them 			
			if (NodesManager.excNodesListModel.isEmpty())
				NodesManager.excNodesListModel.addElement("No excitatory node");			
			if (NodesManager.inhNodesListModel.isEmpty())
				NodesManager.inhNodesListModel.addElement("No inhibitory node");	
			
			//NodesManager.mainPanel.revalidate();
			//NodesManager.mainPanel.repaint();
		}
				
	}

}
