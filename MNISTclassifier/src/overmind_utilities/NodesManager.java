package overmind_utilities;

import java.util.ArrayList;

import javax.swing.DefaultListModel;

import overmind_server.Node;

public class NodesManager {
	static ArrayList<Node> excNodes = new ArrayList<>(); // Nodes with excitatory neurons only 
	static ArrayList<Node> inhNodes = new ArrayList<>(); // Nodes with inhibitory neurons only 

	static DefaultListModel<String> excNodesListModel = new DefaultListModel<>();
	static DefaultListModel<String> inhNodesListModel = new DefaultListModel<>();
}
