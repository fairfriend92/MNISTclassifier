package mnist_classifier_package;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import com.example.overmind.Terminal;

import overmind_server.*;
import overmind_utilities.*;

public class MNISTmain {
	
	/* Constants */
	
	private final static int SUCCESS = 1, ERROR = 0, 
			IMG_FILE_MN = 2051, // Image file magic number
			LBL_FILE_MN = 2049; // Label file magic number

	/* Panels */
	
	private static JPanel logPanel = new JPanel();
	static JPanel mainPanel = new JPanel();
	
	/* Buttons */
	
	private static JButton addNodeToExc = new JButton("Add");
	//private static JButton addNodeToInh = new JButton("Add");
	private static JButton removeNodeFromExc = new JButton("Remove");
	//private static JButton removeNodeFromInh = new JButton("Remove");
	private static JButton createTerminals = new JButton("Create terminals");
	private static JButton trainNetwork = new JButton("Train");
	private static JButton analyzeSamples = new JButton("Analyze");
	private static JButton storeWeights = new JButton("Store weights");
	private static JButton loadWeights = new JButton("Load weights");
	
	/* Miscellanea */
	
	static float[][] trainingSetImages, testSetImages;
	static int[] trainingSetLabels, testSetLabels;	
	static boolean isTraining = false; // Flag that tells if the train button has been pressed and if the network is being trained. 
	static boolean networkWasTrained = false;
	static Terminal thisApp = new Terminal();
    static ArrayList<Terminal> inputTerminals; // The terminals objects that represent the input layer

	/* Threading objects */
	
	private static Thread networkTrainerThread;
	private static Thread analyzerThread;
	
	/* Custom classes */
	
	private static MNISTnetworkTrainer networkTrainer = new MNISTnetworkTrainer();
	
	public static void main(String[] args) {
				
		AppInfo.name = "MNISTclassifier";
					
		ServerInterfacer serverInterfacer = new ServerInterfacer(args);
		serverInterfacer.start();
		
		if (loadDatasets() == ERROR) {
			System.out.println("Error! Problem occurred while loading a dataset");
			return;
		}				
		
		thisApp.numOfNeurons = (short) MNISTconst.MAX_PIC_PIXELS;
		thisApp.numOfSynapses = Short.MAX_VALUE;
		thisApp.numOfDendrites = 0;
		thisApp.ip = Constants.USE_LOCAL_CONNECTION ? VirtualLayerManager.localIP : VirtualLayerManager.serverIP;
		thisApp.serverIP = thisApp.ip;
		thisApp.natPort = MNISTconst.APP_UDP_PORT;
		thisApp.id = thisApp.customHashCode();
		
		displayMainFrame();
		
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
	        public void run() {	        	
	        	serverInterfacer.shutdown = true;
	        	MNISTnetworkTrainer.shutdown = true;
	        		        	
	        	serverInterfacer.interrupt();        	
        	
	        	try {
					serverInterfacer.join();
					if (networkTrainerThread != null)
						networkTrainerThread.join();
					if (analyzerThread != null)
						analyzerThread.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
	        	
	            System.out.println("MNISTclassifier: Orderly shutdown succesfull");
	        }
	    }, "Shutdown-thread"));
	}
	
	private static void displayMainFrame() {
		JPanel commandsPanel = new JPanel();
		JPanel excNodesPanel = new JPanel();
		//JPanel inhNodesPanel = new JPanel();
		JPanel upperPanelsContainer = new JPanel();
		
		JFrame mainFrame = new JFrame();	
				
		JList<String> excNodesList = new JList<>();
		//JList<String> inhNodesList = new JList<>();
		
		JScrollPane excNodesScrollPane = new JScrollPane();
		//JScrollPane inhNodesScrollPane = new JScrollPane();		
		
		/*
		 * Build the individual panels.
		 */
		
		/* Commands panel */
		
		commandsPanel.setLayout(new GridLayout(5, 1));
		commandsPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Commands"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		commandsPanel.add(createTerminals);
		commandsPanel.add(trainNetwork);
		commandsPanel.add(analyzeSamples);
		commandsPanel.add(storeWeights);
		commandsPanel.add(loadWeights);
		
		/* Log panel */
		
		logPanel.add(new JLabel("Log info are shown here."));
		
		/* Excitatory nodes panel */
		
		excNodesPanel.setLayout(new BoxLayout(excNodesPanel, BoxLayout.Y_AXIS));
		excNodesPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Exc. nodes"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		
		excNodesList.setVisibleRowCount(2);
		
		excNodesScrollPane.setViewportView(excNodesList);
		excNodesScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		NodesManager.excNodesListModel.addElement("No excitatory node");
		excNodesList.setModel(NodesManager.excNodesListModel);
		excNodesList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
		excNodesList.setLayoutOrientation(JList.VERTICAL);
		
		excNodesPanel.add(addNodeToExc);
		excNodesPanel.add(removeNodeFromExc);
		excNodesPanel.add(excNodesScrollPane);
		
		/* Inhibitory nodes panel */
		
		/*
		inhNodesPanel.setLayout(new BoxLayout(inhNodesPanel, BoxLayout.Y_AXIS));
		inhNodesPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Inh. nodes"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		
		inhNodesList.setVisibleRowCount(2);
		
		inhNodesScrollPane.setViewportView(inhNodesList);
		inhNodesScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		NodesManager.inhNodesListModel.addElement("No inhibitory node");
		inhNodesList.setModel(NodesManager.inhNodesListModel);
		inhNodesList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
		inhNodesList.setLayoutOrientation(JList.VERTICAL);
		
		inhNodesPanel.add(addNodeToInh);
		inhNodesPanel.add(removeNodeFromInh);
		inhNodesPanel.add(inhNodesScrollPane, BorderLayout.CENTER);
		*/
		
		/* Upper panels container */
		
		upperPanelsContainer.setLayout(new BoxLayout(upperPanelsContainer, BoxLayout.X_AXIS));
		upperPanelsContainer.add(commandsPanel);
		upperPanelsContainer.add(excNodesPanel);
		//upperPanelsContainer.add(inhNodesPanel);
		
		/* Main panel */
				
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.add(upperPanelsContainer);
		mainPanel.add(logPanel);
		
		/*
		 * Define buttons actions. 
		 */
		
		createTerminals.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				if (NodesManager.excNodes.size() == 0) {
					updateLogPanel("Select at least one exc node", Color.RED);
				} else {
					inputTerminals = NetworkStimulator.createInputTerminals(MNISTconst.NUM_OF_LABELS, MNISTconst.MAX_PIC_PIXELS);	
					ArrayList<Node> nodesToUpdate = new ArrayList<Node>();
					for (Node node : NodesManager.excNodes) {
						for (Terminal terminal : inputTerminals) {
							node.terminal.presynapticTerminals.add(terminal);
						}
						
						// Ordering the presynaptic terminals is important for the mapping between known ports and the information
						// included in the headers of the UDP packets that happens on the client side
						Collections.sort(node.terminal.presynapticTerminals);
						
						nodesToUpdate.add(node);
					}		   
					
					updateLogPanel("Terminals created", Color.BLACK);

					//Node[] nodes = new Node[nodesToUpdate.size()]		   ;     
					//VirtualLayerManager.connectNodes(nodesToUpdate.toArray(nodes));		        	       
				}
			}				
		});
		
		trainNetwork.addActionListener(new ActionListener() { 
			@Override
			public void actionPerformed(ActionEvent arg0) {
				if (!isTraining) {			
					if (NodesManager.excNodes.isEmpty()) { 
						updateLogPanel("Select an input and output layer first", Color.RED);
					} else {
						changePanelState(false); // During the learning phase the user shouldn't change the network topology.	
						
						isTraining = true;
						
						networkTrainerThread = new Thread() {
							@Override
							public void run () {
								super.run();
								boolean operationSuccessful = true; 
										
								if (operationSuccessful)
									operationSuccessful &= networkTrainer.setSynapticWeights(); 
								if (operationSuccessful) {
									trainNetwork.setEnabled(true);
									trainNetwork.setText("Stop");
									operationSuccessful &= networkTrainer.classifyInput(true);
								}
								if (operationSuccessful)
									operationSuccessful &= networkTrainer.stopLearning();
																				
								if (!operationSuccessful) {
									NodesManager.resetNetwork(thisApp);
									networkWasTrained = false;									
									changePanelState(true);									
								} else {								
									networkWasTrained = true;
									updateLogPanel("Training completed", Color.BLACK);
								}
								
								isTraining = false;
								trainNetwork.setText("Train");
								changePanelState(true);
							}
						};
						networkTrainerThread.start();						
					}
				} else {
					MNISTnetworkTrainer.analysisInterrupt.set(true);
				}
			}
		});
		
		analyzeSamples.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				if (!networkWasTrained) {
					updateLogPanel("Train the network first", Color.RED);
				} else {
					changePanelState(false);
					
					analyzerThread = new Thread() {
						@Override
						public void run() {
							super.run();
							boolean operationSuccessful = true;
							
						    operationSuccessful &= networkTrainer.classifyInput(false);
							
							if (!operationSuccessful) {
								NodesManager.resetNetwork(thisApp);
								networkWasTrained = false; // TODO: Necessary?									
							} else {								
								updateLogPanel("Analysis completed", Color.BLACK);
							}
							changePanelState(true);
						}
					};
					analyzerThread.start();
				}
			}			
		});
		
		storeWeights.addActionListener(new ActionListener() { 
			@Override
			public void actionPerformed(ActionEvent arg0) {
				if (networkWasTrained) {
					WeightsFile weightsFile = new WeightsFile(NodesManager.excNodes, NodesManager.inhNodes);
					String absolutePath = new File("").getAbsolutePath();
					String weightsPath = absolutePath.concat("/resources/weights");
					File weightsDirectory = new File(weightsPath);
					try {
						File tempWeightsFile = File.createTempFile("tmpName", ".wght", weightsDirectory);
						FileOutputStream fileOutputStream = new FileOutputStream(tempWeightsFile); 
						ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream); 
						objectOutputStream.writeObject(weightsFile);					
						fileOutputStream.close();
						objectOutputStream.close();
					} catch (IOException e) {
						e.printStackTrace();
					} 
					updateLogPanel("Weights stored", Color.BLACK);
				} else {
					updateLogPanel("Train network first", Color.RED);
				}
			}
				
		});
		
		loadWeights.addActionListener(new ActionListener() { 
			@Override
			public void actionPerformed(ActionEvent arg0) {
				// Get the files storing all the weights previously saved.
				String absolutePath = new File("").getAbsolutePath();
				String weightsPath = absolutePath.concat("/resources/weights");
				File weightsDir = new File(weightsPath);
				File[] weightsFiles = weightsDir.listFiles();
				
				if (weightsFiles.length == 0 | weightsFiles == null) {
					updateLogPanel("No weights to load", Color.RED);
				} else {
					// Order the Files by their names.
				 	Arrays.sort(weightsFiles);
				 	boolean rightWeightsFound = false;
				 	
				 	// Look for a file with the right combination of weights for the current nodes.
				 	for (File tmpWeightsFile : weightsFiles) {
						try {
							// Get the WeightsFile object.
							FileInputStream fileInputStream = new FileInputStream(tmpWeightsFile);
							ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
			        		WeightsFile weightsFile = (WeightsFile)objectInputStream.readObject();
			        		
			        		// Check that the number of nodes whose weights were stored equals that of 
			        		// the nodes that constitute the current network. 
			        		boolean conditionsSatisfied = true;
			        		conditionsSatisfied &= weightsFile.excNodesWeights.length == NodesManager.excNodes.size();
			        		conditionsSatisfied &= weightsFile.inhNodesWeights.length == NodesManager.inhNodes.size();
			        		
			        		if (conditionsSatisfied) {
			        			// Was it possible to find the right weights for the excitatory and the inhibitory nodes?
		        				boolean excNodeWeightsFound = true, inhNodeWeightsFound = true;
		        				
		        				/*
		        				 * Each set of weights is characterized by the number of neurons and synapses of the 
		        				 * node they belonged too. For each of the current nodes, find a set with the right
		        				 * characteristics. 
		        				 */		        					        			
		        				
			        			for (int i = 0; i < NodesManager.excNodes.size(); i++) {
			        				Node excNode = NodesManager.excNodes.get(i); // Current node.
			        				boolean weightsFound = false; // Weights compatible with the current node were found?
			        				
			        				// Search among the set of weights stored in the current weights file. 
			        				for (int j = 0; j < weightsFile.excNodesWeights.length; j++) {
			        					
			        					/*
			        					 * The equals method of NodeWeights compare both the number of synapses and the 
			        					 * number of neurons of the node being passed with those stored by NodeWeights
			        					 * itself. 
			        					 */
			        					
			        					if (weightsFile.excNodesWeights[j].equals(excNode)) {
			        						excNode.terminal.newWeights = weightsFile.excNodesWeights[j].weights;
			        						excNode.terminal.newWeightsIndexes = new int[] {0};
			        										
			        						VirtualLayerManager.unsyncNodes.add(excNode);
			        						weightsFound = true;
			        					}
			        				}			        				
			        				excNodeWeightsFound &= weightsFound;
			        			}
			        			
			        			/*
			        			 * Just like above, but now for the inhibitory nodes.
			        			 */
			        			
			        			for (int i = 0; i < NodesManager.inhNodes.size(); i++) {
			        				Node inhNode = NodesManager.inhNodes.get(i); 
			        				boolean weightsFound = false; 
			        				
			        				for (int j = 0; j < weightsFile.inhNodesWeights.length; j++) {
			        					if (weightsFile.inhNodesWeights[j].equals(inhNode)) {
			        						inhNode.terminal.newWeights = weightsFile.inhNodesWeights[j].weights;
			        						inhNode.terminal.newWeightsIndexes = new int[] {0};
			        										
			        						VirtualLayerManager.unsyncNodes.add(inhNode);
			        						weightsFound = true;
			        					}
			        				}			        				
			        				inhNodeWeightsFound &= weightsFound;
			        			}
			        		
			        			rightWeightsFound = excNodeWeightsFound & inhNodeWeightsFound;
			        		}
			        		/* [End of if (conditionsSatsfied)] */
			        		
						} catch (ClassNotFoundException | IOException e) {
							e.printStackTrace();
						}	
						
						if (rightWeightsFound) break;
				 	}
				 	/* [End of for (File tmpWeightsFile : weightsFiles)] */
				 	
				 	if (rightWeightsFound) {
						updateLogPanel("Weights loaded", Color.BLACK);
						
						Future<Boolean> future = VirtualLayerManager.syncNodes();
						// Wait for the synchronization process to be completed before proceeding.
						try {
							Boolean syncSuccessful = future.get();
							if (!syncSuccessful)
								updateLogPanel("Weights update interrupted", Color.RED);
						} catch (InterruptedException | ExecutionException e) {
							updateLogPanel("Weights update interrupted", Color.RED);
						}
						
						changePanelState(false); 
						networkTrainerThread = new Thread() {
							
							/*
							 * Just like when the training button is pressed, but the weights are not
							 * randomized. 
							 * (non-Javadoc)
							 * @see java.lang.Thread#run()
							 */
							
							@Override
							public void run () {
								super.run();
								boolean operationSuccessful = true; 
											
								if (operationSuccessful) {
									trainNetwork.setEnabled(true);
									trainNetwork.setText("Stop");
									operationSuccessful &= networkTrainer.classifyInput(true);
								}
																				
								if (!operationSuccessful) {
									NodesManager.resetNetwork(thisApp);
									networkWasTrained = false;									
								} else {								
									networkWasTrained = true;
									updateLogPanel("Training completed", Color.BLACK);
								}
								
								isTraining = false;
								trainNetwork.setText("Train");
								changePanelState(true);
							}
						};
						networkTrainerThread.start();						
				 	} else {
						updateLogPanel("No right weights for the nodes", Color.RED);
				 	}
				}
				/* [End of if (weightsFiles.length == 0 | weightsFiles == null)] */
			}
		});
		
		addNodeToExc.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				Node selectedNode = VirtualLayerVisualizer.selectedNode;
				
				if (selectedNode == null) {
					updateLogPanel("No node selected.", Color.RED); 
				} else if (NodesManager.excNodes.contains(selectedNode)){
					updateLogPanel("Node is already present.", Color.RED); 
				} else if (NodesManager.inhNodes.contains(selectedNode)) {
					updateLogPanel("Selected node is already inhibitory.", Color.RED); 
				} else {
					updateLogPanel("Node added to exc. nodes.", Color.BLACK); 
					
					// Prevent the user from stimulating the terminal.
					//selectedNode.terminalFrame.noneRadioButton.doClick();
					selectedNode.terminalFrame.randomSpikesRadioButton.setEnabled(false);
					selectedNode.terminalFrame.refreshSignalRadioButton.setEnabled(false);
					selectedNode.isExternallyStimulated = true;
					
					if (NodesManager.excNodesListModel.contains("No excitatory node")) // The default message should be cleared if a node is added to the list.
						NodesManager.excNodesListModel.clear();
					
					NodesManager.excNodesListModel.addElement(selectedNode.terminal.ip);
					NodesManager.excNodes.add(selectedNode);
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});
		
		removeNodeFromExc.addActionListener(new ActionListener() {			
			@Override
			public void actionPerformed(ActionEvent arg0) {
				int selectionIndex = excNodesList.getSelectedIndex();
				
				if(selectionIndex == -1) {
					updateLogPanel("Select an exc. node first.", Color.RED); 
				} else if (NodesManager.excNodesListModel.contains("No excitatory node")) {
					updateLogPanel("No exc. nodes to remove.", Color.RED); 
					
				} else {
					
			    	/* 
			    	 * Delete the input sender from the list of presynaptic connections.
			    	 */			        
			    	
					// For each node check if it was connected to a socket opened by this app. If so, eliminate the connection and sync the node.
			        for (Node excNode : NodesManager.excNodes) {
			        	int result = NodesManager.removeAppFromConnections(excNode, thisApp);
			        	if (result == UtilConst.SUCCESS)
			        		VirtualLayerManager.unsyncNodes.add(excNode);
			        }	         
			        
			        Boolean syncSuccessful = true; 
			        
			        // If for some node a connection to the app was found, sync the terminal info on the server with those on the physical device.
			        if (VirtualLayerManager.unsyncNodes.size() != 0) {
				        Future<Boolean> future = VirtualLayerManager.syncNodes();	  			        
						try {
							syncSuccessful = future.get();
							// If the stream was interrupted remove the node from the server. 
							if (!syncSuccessful) {
								updateLogPanel("TCP stream interrupted", Color.RED);
								VirtualLayerManager.removeNode(NodesManager.excNodes.get(selectionIndex), true);
							} 
						} catch (InterruptedException | ExecutionException e) {
							e.printStackTrace();
						}
			        }
					
			        // If the sync was successful or if it didn't take place, restore the settings of node and the terminal frame. 
			        if (syncSuccessful) {			        
			        	NodesManager.excNodes.get(selectionIndex).isExternallyStimulated = false;
			        	NodesManager.excNodes.get(selectionIndex).terminalFrame.randomSpikesRadioButton.setEnabled(true);
			        	NodesManager.excNodes.get(selectionIndex).terminalFrame.refreshSignalRadioButton.setEnabled(true);
			        }
															
			        NodesManager.excNodes.remove(selectionIndex);
			        NodesManager.excNodesListModel.remove(selectionIndex);
					
					updateLogPanel("Node removed from exc. nodes.", Color.BLACK);
					
					if (NodesManager.excNodesListModel.isEmpty())
						NodesManager.excNodesListModel.addElement("No excitatory node");
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});		
		
		/*
		 * Create the frame. 
		 */
		
		mainFrame.setTitle("MNISTclassifier");
		mainFrame.setContentPane(mainPanel);
		mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		mainFrame.pack();
		mainFrame.setAlwaysOnTop(true);
		mainFrame.setVisible(true);		
	}
	
	private static void changePanelState(boolean state) {
		addNodeToExc.setEnabled(state);
		//addNodeToInh.setEnabled(state);
		removeNodeFromExc.setEnabled(state);
		//removeNodeFromInh.setEnabled(state);
		trainNetwork.setEnabled(state);
		analyzeSamples.setEnabled(state);
		loadWeights.setEnabled(state);
		storeWeights.setEnabled(state);
		
		mainPanel.repaint();
		mainPanel.revalidate();
	}
	
	/**
	 * Show text on the log panel.
	 */
	
	static void updateLogPanel(String logText, Color color) {
		logPanel.removeAll();
		JLabel logMessage = new JLabel(logText);
		logMessage.setForeground(color);
		logPanel.add(logMessage);
		logPanel.repaint();
		logPanel.revalidate();
	}
	
	private static int loadDatasets() {
		// Get the files the MNIST dataset pictures and labels
		String absolutePath = new File("").getAbsolutePath();
		String mnistPath = absolutePath.concat("/resources/MNIST");
		File mnistDir = new File(mnistPath);
		File[] mnistFiles = mnistDir.listFiles();
		
		// Assign the files to the right references
		File trainingSetImagesFile = null, trainingSetLabelsFile = null, 
				testSetImagesFile = null, testSetLabelsFile = null;
		
		for (File mnistFile : mnistFiles) {
			String fileName = mnistFile.getName();
			switch (fileName) {
			
			case "train-images-idx3-ubyte": trainingSetImagesFile = mnistFile;
			trainingSetImages = readImageFile(trainingSetImagesFile);
			if (trainingSetImages == null) {
				System.out.println("Error! readImageFile failed for trainingSetImages");
				return ERROR;
			}
			break;
			
			case "train-labels-idx1-ubyte": trainingSetLabelsFile = mnistFile;
			trainingSetLabels = readLabelFile(trainingSetLabelsFile);
			if (trainingSetLabels == null) {
				System.out.println("Error! readLabelFile failed for trainingSetLabels");
				return ERROR; 
			}
			break;
			
			case "test-images-idx3-ubyte": testSetImagesFile = mnistFile;
			testSetImages = readImageFile(testSetImagesFile);
			if (testSetImages == null) {
				System.out.println("Error! readImageFile failed for testSetImages");
				return ERROR;
			}
			break;
			
			case "test-labels-idx1-ubyte": testSetLabelsFile = mnistFile;
			testSetLabels = readLabelFile(testSetLabelsFile);
			if (testSetLabels == null) {
				System.out.println("Error! readLabelFile failed for testSetLabels");
				return ERROR;
			}
			break;
			}
		}
		
		System.out.println("All images and labels loaded");	
		
		return SUCCESS;
	}
	
	private static float[][] readImageFile(File imageFile) {
		float[][] result = null;
		
		try {
			FileInputStream fileInputStream = new FileInputStream(imageFile);
			byte[] buffer = new byte[4];
			
			// Check that the header of the file is correct
			fileInputStream.read(buffer);
			int magicNumber = new BigInteger(buffer).intValue(); 
			if (magicNumber != IMG_FILE_MN) { 
				System.out.println("Error! Header of " + imageFile.getName() + " did not check out");
				fileInputStream.close();				
				return null;
			}
			
			fileInputStream.read(buffer);
			int numOfImages = new BigInteger(buffer).intValue();	
			
			fileInputStream.read(buffer);
			int numOfRows = new BigInteger(buffer).intValue();
			
			fileInputStream.read(buffer);
			int numOfColumns = new BigInteger(buffer).intValue();			
			
			System.out.println(
					imageFile.getName() + " " + numOfImages + " " + numOfRows + " " + numOfColumns);
									
			buffer = new byte[1];
			
			// Read the images and save their pixels in a double array
			// Images are read row-wise, original bytes are unsigned
			int imageResolution = numOfRows * numOfColumns; 
			float[][] images = new float[numOfImages][imageResolution];
			for (int i = 0; i < numOfImages; i++)
				for (int j = 0; j < imageResolution; j++) {
					fileInputStream.read(buffer);
					images[i][j] = buffer[0];
				}
			
			fileInputStream.close();
			result = images;
		} catch (IOException e) {
			e.printStackTrace();
		} 
		
		return result;
	}
	
	private static int[] readLabelFile(File labelFile) {
		int[] result = null;
		
		try {
			FileInputStream fileInputStream = new FileInputStream(labelFile);
			byte[] buffer = new byte[4];
			
			// Check that the header of the file is correct
			fileInputStream.read(buffer);
			int magicNumber = new BigInteger(buffer).intValue(); 
			if (magicNumber != LBL_FILE_MN) { 
				System.out.println("Error! Header of " + labelFile.getName() + " did not check out");
				fileInputStream.close();				
				return null;
			}
			
			fileInputStream.read(buffer);
			int numOfItems = new BigInteger(buffer).intValue();	
			
			System.out.println(labelFile.getName() + " " + numOfItems);
									
			buffer = new byte[1];
			
			// Read the labels
			int[] labels = new int[numOfItems];
			for (int i = 0; i < numOfItems; i++) {
				fileInputStream.read(buffer);
				labels[i] = buffer[0];
			}
			
			fileInputStream.close();
			result = labels;
		} catch (IOException e) {
			e.printStackTrace();
		} 
		
		return result;
	}		
}