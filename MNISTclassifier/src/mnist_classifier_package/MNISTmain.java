package mnist_classifier_package;
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
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

import com.example.overmind.Population;
import com.example.overmind.Terminal;

import overmind_server.*;
import overmind_utilities.*;

public class MNISTmain {
	
	/* Constants. */
	
	private final static int SUCCESS = 1, ERROR = 0, 
			IMG_FILE_MN = 2051, // Image file magic number.
			LBL_FILE_MN = 2049; // Label file magic number.

	/* Panels and frames. */
	
	private static JPanel logPanel = new JPanel();
	private static JFrame pictureFrame = new JFrame();
	static PicturePanel picturePanel;
	static JPanel mainPanel = new JPanel();
	
	/* Buttons. */
	
	private static JButton addNodeToExc = new JButton("Add");
	private static JButton removeNodeFromExc = new JButton("Remove");
	private static JButton createTerminals = new JButton("Create terminals");
	private static JButton trainNetwork = new JButton("Train");
	private static JButton analyzeSamples = new JButton("Analyze");
	private static JButton storeWeights = new JButton("Store weights");
	private static JButton loadWeights = new JButton("Load weights");
	private static JButton loadData = new JButton("Load MNIST data");
	
	/* Miscellanea. */
	
	static ArrayList<float[]> trainingSetImages, testSetImages;
	static ArrayList<Integer> trainingSetLabels, testSetLabels;	
	static boolean isTraining = false; // Flag that tells if the train button has been pressed and if the network is being trained. 
	static boolean networkWasTrained = false;
	static Terminal thisApp = new Terminal();
    static ArrayList<Terminal> inputTerminals; // The terminals objects that represent the input layer.
    static Integer numOfTrainingImgs = null, numOfTestImgs = null;

	/* Threading objects. */
	
	private static Thread networkTrainerThread;
	private static Thread analyzerThread;
	
	public static Integer rareDigit = null;
	
	/* Custom classes. */
	
	private static MNISTnetworkTrainer networkTrainer = new MNISTnetworkTrainer();
	
	public static void main(String[] args) {
				
		AppInfo.name = "MNISTclassifier";
					
		ServerInterfacer serverInterfacer = new ServerInterfacer(args);
		serverInterfacer.start();
		
		// Gives time to the main thread to retrieve the IP of the server.	
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
		
		displayFrames();
		
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
	
	private static void displayFrames() {
		JPanel commandsPanel = new JPanel();
		JPanel excNodesPanel = new JPanel();
		JPanel upperPanelsContainer = new JPanel();
		JPanel dataPanel = new JPanel();
		JPanel rareEventsPanel = new JPanel();
		
		JCheckBox rareEventsCheckBox = new JCheckBox("Rare events", false);
		
		// The maximum number of images is hard-coded as it is known beforehand for the MNIST dataset.
		SpinnerNumberModel trainingSpinnerModel = new SpinnerNumberModel(1, 1, 60000, 1);
		SpinnerNumberModel testSpinnerModel = new SpinnerNumberModel(1, 1, 10000, 1);
		SpinnerNumberModel rareEventsDigitModel = new SpinnerNumberModel(0, 0, 9, 1);

		JSpinner trainingImagesSpinner = new JSpinner(trainingSpinnerModel);
		JSpinner testImagesSpinner = new JSpinner(testSpinnerModel);
		JSpinner rareEventsDigitSpinner = new JSpinner(rareEventsDigitModel);
		rareEventsDigitSpinner.setEnabled(false);
		
		JFrame mainFrame = new JFrame();
				
		JList<String> excNodesList = new JList<>();		
		JScrollPane excNodesScrollPane = new JScrollPane();
		
		/* Build the panels. */
		
		/* Commands panel. */
		
		commandsPanel.setLayout(new GridLayout(5, 1));
		commandsPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Commands"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		commandsPanel.add(createTerminals);
		commandsPanel.add(trainNetwork);
		commandsPanel.add(analyzeSamples);
		commandsPanel.add(storeWeights);
		commandsPanel.add(loadWeights);
		
		/* Data panel. */
		
		dataPanel.setLayout(new GridLayout(5, 1));
		dataPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Data options"), 
				BorderFactory.createEmptyBorder(5,5,5,5)));
		dataPanel.add(new JLabel("# training imgs"));
		dataPanel.add(trainingImagesSpinner);
		dataPanel.add(new JLabel("# test imgs"));
		dataPanel.add(testImagesSpinner);
		dataPanel.add(loadData);
		
		/* Log panel. */
		
		logPanel.add(new JLabel("Log info are shown here."));
		
		/* Excitatory nodes panel. */
		
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
		
		/* Rare events panel */
		
		rareEventsPanel.setLayout(new GridLayout(5, 1));
		rareEventsPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Rare events"),
				BorderFactory.createEmptyBorder(5, 5, 5, 5)));
		
		rareEventsCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JCheckBox jCheckBox = (JCheckBox) e.getSource();
				if (jCheckBox.isSelected()) {
					rareEventsDigitSpinner.setEnabled(true);
				} else {
					rareEventsDigitSpinner.setEnabled(false);
				}
			}
		});
		
		rareEventsPanel.add(rareEventsCheckBox);
		rareEventsPanel.add(new JLabel("Rare digit"));
		rareEventsPanel.add(rareEventsDigitSpinner);
		
		/* Upper panels container. */
		
		upperPanelsContainer.setLayout(new BoxLayout(upperPanelsContainer, BoxLayout.X_AXIS));
		upperPanelsContainer.add(commandsPanel);
		upperPanelsContainer.add(excNodesPanel);
		upperPanelsContainer.add(dataPanel);
		upperPanelsContainer.add(rareEventsPanel);
		
		/* Main panel. */
				
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.add(upperPanelsContainer);
		mainPanel.add(logPanel);
		
		/* Define buttons actions. */
		
		loadData.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				numOfTrainingImgs = (Integer) trainingImagesSpinner.getValue();
				numOfTestImgs = (Integer) testImagesSpinner.getValue();
							
				updateLogPanel("Loading images...", Color.BLACK);
				
				if (loadDatasets(numOfTrainingImgs, numOfTestImgs) == ERROR) {
					System.out.println("Error! Problem occurred while loading a dataset");
					updateLogPanel("Error loading data", Color.RED);
				} else {
					updateLogPanel("Images loaded", Color.BLACK);
				}
			} 
			
		});
		
		createTerminals.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				if (NodesManager.excNodes.size() == 0) {
					updateLogPanel("Select at least one exc node", Color.RED);
				} else {
					thisApp.numOfNeurons = (short) MNISTconst.MAX_PIC_PIXELS;
					thisApp.numOfSynapses = Short.MAX_VALUE;
					thisApp.numOfDendrites = 0;
					thisApp.ip = Constants.USE_LOCAL_CONNECTION ? VirtualLayerManager.localIP : VirtualLayerManager.serverIP;
					thisApp.serverIP = thisApp.ip;
					thisApp.natPort = MNISTconst.APP_UDP_PORT;
					thisApp.id = thisApp.customHashCode();
					
					inputTerminals = NetworkStimulator.createInputTerminals(MNISTconst.NUM_OF_LABELS, MNISTconst.MAX_PIC_PIXELS);	
					//ArrayList<Node> nodesToUpdate = new ArrayList<Node>();
					for (Node node : NodesManager.excNodes) {
						if (node.terminal.numOfDendrites >= MNISTconst.MAX_PIC_PIXELS) {						
							for (Terminal terminal : inputTerminals) {
								node.terminal.presynapticTerminals.add(terminal);
							}
							
							// TODO: This solution is not good. The problem is caused by the lack of difference
							// between the synapses and dendrites of the terminal and those of the population. 
							node.terminal.numOfDendrites -= MNISTconst.MAX_PIC_PIXELS;
							
							// Connect the excNode to the application. 
							node.terminal.postsynapticTerminals.add(MNISTmain.thisApp);
							node.terminal.numOfSynapses -= node.terminal.numOfNeurons;
							
							// Ordering the presynaptic terminals is important for the mapping between known ports and the information
							// included in the headers of the UDP packets that happens on the client side
							Collections.sort(node.terminal.presynapticTerminals, Comparator.reverseOrder());
												
							//nodesToUpdate.add(node);
							automaticPopulationCreation(node);
						} else {
							updateLogPanel("Node has not enough input synapses!", Color.RED);
						}
					}		   
					
					updateLogPanel("Terminals created", Color.BLACK);	        	       
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
						changeMainPanelState(false); // During the learning phase the user shouldn't change the network topology.	
						
						isTraining = true;
						
						networkTrainerThread = new Thread() {
							@Override
							public void run () {
								super.run();
								boolean operationSuccessful = true; 
										
								if (rareEventsCheckBox.isSelected()) {
									rareDigit = (Integer) rareEventsDigitSpinner.getValue();
								} else {
									rareDigit = null;
								}
								
								if (operationSuccessful & !MNISTnetworkTrainer.shutdown)
									operationSuccessful &= networkTrainer.setSynapticWeights(); 
								
								if (operationSuccessful) {
									trainNetwork.setEnabled(true);
									trainNetwork.setText("Stop");
									operationSuccessful &= networkTrainer.classifyInput(true);
								}
								if (operationSuccessful & !MNISTnetworkTrainer.shutdown)
									operationSuccessful &= networkTrainer.stopLearning();
																				
								if (!operationSuccessful & !MNISTnetworkTrainer.shutdown) {
									NodesManager.resetNetwork(thisApp);
									networkWasTrained = false;									
									changeMainPanelState(true);									
								} else if (!MNISTnetworkTrainer.shutdown) {								
									networkWasTrained = true;
									updateLogPanel("Training completed", Color.BLACK);
								}
								
								if (!MNISTnetworkTrainer.shutdown) {
									isTraining = false;
									trainNetwork.setText("Train");
									changeMainPanelState(true);
								}
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
					changeMainPanelState(false);
					
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
							changeMainPanelState(true);
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
			        		objectInputStream.close();
			        		
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
						
						changeMainPanelState(false); 
						networkTrainerThread = new Thread() {
							
							/*
							 * Just like when the training button is pressed, but the weights are not
							 * randomized. 
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
								changeMainPanelState(true);
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
					
					// The default message should be cleared if a node is added to the list.
					if (NodesManager.excNodesListModel.contains("No excitatory node")) 
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
			    	
					// For each node check if it was connected to a socket opened by this app. 
					// If so, eliminate the connection and sync the node.
			        for (Node excNode : NodesManager.excNodes) {
			        	int result = NodesManager.removeAppFromConnections(excNode, thisApp);
			        	if (result == UtilConst.SUCCESS)
			        		VirtualLayerManager.unsyncNodes.add(excNode);
			        }	         
			        
			        Boolean syncSuccessful = true; 
			        
			        // If for some node a connection to the app was found, 
			        // sync the terminal info on the server with those on the physical device.
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
		
		/* Create the frames. */
		
		mainFrame.setTitle("MNISTclassifier");
		mainFrame.setContentPane(mainPanel);
		mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		mainFrame.pack();
		mainFrame.setAlwaysOnTop(true);
		mainFrame.setVisible(true);		
		
		picturePanel = new PicturePanel(new float[MNISTconst.MAX_PIC_PIXELS], MNISTconst.PIC_SIDE, MNISTconst.PIC_SIDE, 1);
		
		pictureFrame.setTitle("Unlabelled pic");
		pictureFrame.setContentPane(picturePanel);
		pictureFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		pictureFrame.pack();
		pictureFrame.setAlwaysOnTop(true);
		pictureFrame.setVisible(false);
	}
	
	private static void changeMainPanelState(boolean state) {
		addNodeToExc.setEnabled(state);
		removeNodeFromExc.setEnabled(state);
		trainNetwork.setEnabled(state);
		analyzeSamples.setEnabled(state);
		loadWeights.setEnabled(state);
		storeWeights.setEnabled(state);
		createTerminals.setEnabled(state);
		
		mainPanel.repaint();
		mainPanel.revalidate();
	}
	
	static void updatePictureFrame(float[] pixels, int width, int height, int scale, 
			String frameTitle) {
		picturePanel.updatePicturePanel(pixels, width, height, scale);
		pictureFrame.setSize(picturePanel.getPreferredSize().width, 
				picturePanel.getPreferredSize().height);
		if (!pictureFrame.isVisible()) { pictureFrame.setVisible(true); }
		pictureFrame.setTitle(frameTitle);
		pictureFrame.repaint();
		pictureFrame.revalidate();
	}
	
	static void updateLogPanel(String logText, Color color) {
		logPanel.removeAll();
		JLabel logMessage = new JLabel(logText);
		logMessage.setForeground(color);
		logPanel.add(logMessage);
		logPanel.repaint();
		logPanel.revalidate();
	}
	
	private static int loadDatasets(Integer numOfTrainingImgs, Integer numOfTestImgs) {
		// Get the files the MNIST dataset pictures and labels.
		String absolutePath = new File("").getAbsolutePath();
		String mnistPath = absolutePath.concat("/resources/MNIST");
		File mnistDir = new File(mnistPath);
		File[] mnistFiles = mnistDir.listFiles();
		
		// Assign the files to the right references.
		File trainingSetImagesFile = null, trainingSetLabelsFile = null, 
				testSetImagesFile = null, testSetLabelsFile = null;
		
		for (File mnistFile : mnistFiles) {
			String fileName = mnistFile.getName();
			switch (fileName) {
			
			case "train-images-idx3-ubyte": trainingSetImagesFile = mnistFile;
			trainingSetImages = readImageFile(trainingSetImagesFile, numOfTrainingImgs);
			if (trainingSetImages == null) {
				System.out.println("Error! readImageFile failed for trainingSetImages");
				return ERROR;
			}
			break;
			
			case "train-labels-idx1-ubyte": trainingSetLabelsFile = mnistFile;
			trainingSetLabels = readLabelFile(trainingSetLabelsFile, numOfTrainingImgs);
			if (trainingSetLabels == null) {
				System.out.println("Error! readLabelFile failed for trainingSetLabels");
				return ERROR; 
			}
			break;
			
			case "test-images-idx3-ubyte": testSetImagesFile = mnistFile;
			testSetImages = readImageFile(testSetImagesFile, numOfTestImgs);
			if (testSetImages == null) {
				System.out.println("Error! readImageFile failed for testSetImages");
				return ERROR;
			}
			break;
			
			case "test-labels-idx1-ubyte": testSetLabelsFile = mnistFile;
			testSetLabels = readLabelFile(testSetLabelsFile, numOfTestImgs);
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
	
	private static ArrayList<float[]> readImageFile(File imageFile, Integer numOfImgs) {
		ArrayList<float[]> result = null;
		
		try {
			FileInputStream fileInputStream = new FileInputStream(imageFile);
			byte[] buffer = new byte[4];
			
			// Check that the header of the file is correct.
			fileInputStream.read(buffer);
			int magicNumber = new BigInteger(buffer).intValue(); 
			if (magicNumber != IMG_FILE_MN) { 
				System.out.println("Error! Header of " + imageFile.getName() + " did not check out");
				fileInputStream.close();				
				return null;
			}
			
			fileInputStream.read(buffer);
			//int numOfImages = new BigInteger(buffer).intValue();	
			int numOfImages = numOfImgs.intValue();
			
			fileInputStream.read(buffer);
			int numOfRows = new BigInteger(buffer).intValue();
			
			fileInputStream.read(buffer);
			int numOfColumns = new BigInteger(buffer).intValue();			
			
			System.out.println(
					imageFile.getName() + " " + numOfImages + " " + numOfRows + " " + numOfColumns);
									
			buffer = new byte[1];
			
			// Read the images and save their pixels.
			int imageResolution = numOfRows * numOfColumns; 
			ArrayList<float[]> images = new ArrayList<float[]>(numOfImages);
			for (int i = 0; i < numOfImages; i++) {
				float image[] = new float[imageResolution];
				for (int j = 0; j < imageResolution; j++) {
					fileInputStream.read(buffer);
					image[j] = buffer[0] & 0xFF;
				}
				images.add(image);
			}
			
			fileInputStream.close();
			result = images;
		} catch (IOException e) {
			e.printStackTrace();
		} 
		
		return result;
	}
	
	private static ArrayList<Integer> readLabelFile(File labelFile, Integer numOfLabels) {
		ArrayList<Integer> result = null;
		
		try {
			FileInputStream fileInputStream = new FileInputStream(labelFile);
			byte[] buffer = new byte[4];
			
			// Check that the header of the file is correct.
			fileInputStream.read(buffer);
			int magicNumber = new BigInteger(buffer).intValue(); 
			if (magicNumber != LBL_FILE_MN) { 
				System.out.println("Error! Header of " + labelFile.getName() + " did not check out");
				fileInputStream.close();				
				return null;
			}
			
			fileInputStream.read(buffer);
			//int numOfItems = new BigInteger(buffer).intValue();	
			int numOfItems = numOfLabels.intValue();
			
			System.out.println(labelFile.getName() + " " + numOfItems);
									
			buffer = new byte[1];
			
			// Read the labels.
			ArrayList<Integer> labels = new ArrayList<>(numOfItems);
			for (int i = 0; i < numOfItems; i++) {
				fileInputStream.read(buffer);
				labels.add(Integer.valueOf((int)buffer[0]));
			}
			
			fileInputStream.close();
			result = labels;
		} catch (IOException e) {
			e.printStackTrace();
		} 
		
		return result;
	}		
	
	/**
	 * The method creates 10 population of NUM_OF_NEURONS neurons each, 
	 * and connects them to the presynaptic terminals created by the application
	 * and the postsynaptic terminals that represent the server and 
	 * the application itself.
	 * @param node The node that must be partitioned in populations.
	 */
	
	private static void automaticPopulationCreation(Node node) {
		final int NUM_OF_NEURONS = 8;
		
		Population[] pops = new Population[10];
		
		// TODO: For the first time here we intend the number of dendrites/synapses as the number of 
		// used connections, not the number of available ones. This is different from the rest of the code.		
		for (int i = 0; i < MNISTconst.NUM_OF_LABELS; i++) {
			
			int neededInputSynapses = node.hasLateralConnections() ? 
					MNISTconst.MAX_PIC_PIXELS + NUM_OF_NEURONS * MNISTconst.NUM_OF_LABELS :
						MNISTconst.MAX_PIC_PIXELS;
			
			// The number of output synapses is equal to the number of neurons as the whole output is sent 
			// to the server terminal only.									
			pops[i] = new Population((short)NUM_OF_NEURONS, MNISTconst.MAX_PIC_PIXELS, (short)NUM_OF_NEURONS);
			pops[i].inputIndexes.add(inputTerminals.get(i).id);
			
			if (node.hasLateralConnections()) {
				pops[i].inputIndexes.add(node.terminal.id);
			} 
			
			// TODO: When multiplexing of the outputs is implemented client-side, the application
			// terminal should be added too and the number of synapses of the population should be doubled. 
			pops[i].outputIndexes.add(VirtualLayerManager.thisServer.id);
			
			node.terminal.addPopulation(pops[i]);
						
		}	
		
		VirtualLayerVisualizer.partTool.selectedNode = node;
		VirtualLayerVisualizer.partTool.buildPopulationsMatrix(node.terminal.populations, node.terminal);
	}
}