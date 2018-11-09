package mnist_classifier_package;
import java.awt.Color;
import overmind_server.*;
import overmind_utilities.GrayscaleCandidate;
import overmind_utilities.NetworkStimulator;
import overmind_utilities.NodesManager;
import overmind_utilities.UtilConst;

import java.awt.List;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.overmind.Population;
import com.example.overmind.Terminal;

public class MNISTnetworkTrainer {	
	// Stores for each node the firing rates in response to an input that must be classified.
	private static ConcurrentHashMap<Integer, float[]> untaggedFiringRateMap;  

	// Number that keeps track of which kind of input is being used to stimulate the network.  
	private static volatile int currentInputClass = MNISTconst.UNDETERMINED;
		
	// Object used to synchronize the worker thread of SpikesReceiver with thread on which 
	// NetworkTrainer runs
	private final static Object lock = new Object();
	
	// Constants local to this class.
	private final byte UPDATE_WEIGHT = (byte)1;
	private final byte DONT_UPDATE_WEIGHT = (byte)0;
	private final static int NO_INPUT = -1;
	
	// Flags that control the execution of the code
	static boolean shutdown = false;
	static AtomicBoolean analysisInterrupt = new AtomicBoolean(false);
	
	com.example.overmind.Terminal thisApp = new com.example.overmind.Terminal();    

					
	/**
	 * Class that waits for UDP packets to arrive at a specific port and
	 * update the firing rates of the neurons that produced the spikes.  
	 */
	
	private static class MuonTeacherSpikesReceiver extends Thread {	
		
		private BlockingQueue<WorkerThread> threadsDispatcherQueue = new ArrayBlockingQueue<>(128);
		boolean shutdown = false; // This shutdown should be independent of the main one. 
		boolean isTrainingSession = false;
		DatagramSocket socket;
		
		MuonTeacherSpikesReceiver(boolean isTrainingSession) {
			this.isTrainingSession = isTrainingSession;
		}
		
		/**
		 * Inner class that implements Runnable and takes care of updating the 
		 * firing rates of the neurons that belong to the node that has sent 
		 * the spikesPacket.  
		 */
		
		private class WorkerThread implements Runnable {
			DatagramPacket spikesPacket;
			byte[] spikesBuffer;
			
			WorkerThread(DatagramPacket spikesPacket, byte[] spikesBuffer) {
				this.spikesPacket = spikesPacket;
				this.spikesBuffer = spikesBuffer;
			}
			
			@Override
			public void run() {
        		if (spikesPacket != null) {     			
        			String ip = spikesPacket.getAddress().toString().substring(1);
        			int natPort = spikesPacket.getPort();
        			byte[] firstHalf = ip.getBytes();
    		    	byte secondHalf = new Integer(natPort).byteValue();
    		    	byte[] data = new byte[firstHalf.length + 1];
    		    	System.arraycopy(firstHalf, 0, data, 0, firstHalf.length);
    		    	data[firstHalf.length] = secondHalf;
    		    	
    		    	// Implementation of the FNV-1 algorithm
    		    	int hash = 0x811c9dc5;    	
    		    	for (int i = 0; i < data.length; i++) {
    		    		hash ^= (int)data[i];
    		    		hash *= 16777619;
    		    	}		  
        			int numOfNeurons = VirtualLayerManager.nodesTable.get(VirtualLayerManager.physical2VirtualID.get(hash)).terminal.numOfNeurons;         		
        		        			
          			float[] meanFiringRates = null;
          			
          			if (!isTrainingSession) {
          				// Vector of the firing rates that must be compared. 
          				Integer id = VirtualLayerManager.physical2VirtualID.get(hash);
        				meanFiringRates = untaggedFiringRateMap.get(id);  
        			}           			
        			assert meanFiringRates != null;        			
        			
        			// Iterating over the the neurons that produced the spike trains.
        			for (int neuronIndex = 0; neuronIndex < numOfNeurons; neuronIndex++) { 
        				int byteIndex = neuronIndex / 8;
        				
        				// If the current neuron had emitted a spike, increase the firing rate using a simple moving average algorithm. 
        				meanFiringRates[neuronIndex] += ((spikesBuffer[byteIndex] >> (neuronIndex - byteIndex * 8)) & 1) == 1 ? 
        						+ MNISTconst.MEAN_RATE_INCREMENT * (1 - meanFiringRates[neuronIndex]) : 
        							- MNISTconst.MEAN_RATE_INCREMENT * meanFiringRates[neuronIndex];	       	
        			}	        		
        		}
        		/* [End of if (spikesPacket != null)] */
			}
    		/* [End of run] */
		}
		/* [End of inner class] */        		
		
		/**
		 * Inner class whose job is that of creating a new thread for each node and to dispatch
		 * the incoming Runnables from the run() method of the outer class to said threads. 
		 * @author rodolfo
		 *
		 */
		
		private class ThreadsDispatcher implements Runnable {
			
			private ExecutorService workerThreadsExecutor = Executors.newFixedThreadPool(NodesManager.excNodes.size());	
			private HashMap<Integer, Future<?>> futuresMap = new HashMap<>(NodesManager.excNodes.size());
		
			@Override
			public void run() {
				while (!shutdown) {
					WorkerThread workerThread = null;
					try {
						workerThread = threadsDispatcherQueue.poll(1, TimeUnit.SECONDS); // Polling is necessary so that the operation doesn't block an eventual shutdown. 
					} catch (InterruptedException e) {
						e.printStackTrace();
					}	
															
					if (workerThread != null) {
						int ipHashCode = (workerThread.spikesPacket.getAddress().toString().substring(1) + 
								"/" + workerThread.spikesPacket.getPort()).hashCode();
						Future<?> future = futuresMap.get(ipHashCode);
						
						// If a thread for the node corresponding to ipHashCode was created before,
						// wait for its task to complete. 
						if (future != null) {
							try {
								future.get(); // Worker thread has no blocking operation, so no need to poll. 
							} catch (InterruptedException | ExecutionException e) {
								e.printStackTrace();
							}
						} 

						// Dispatch a new thread and store the associated future in the hash map. 
						future = workerThreadsExecutor.submit(workerThread);
						futuresMap.put(ipHashCode, future);						
					}
					
				}
				
		    	/* Shutdown executor. */
				
				workerThreadsExecutor.shutdown();	    	
		    	try {
		    		boolean workerThreadsExecutorIsShutdown = workerThreadsExecutor.awaitTermination(1, TimeUnit.SECONDS);
		    		if (!workerThreadsExecutorIsShutdown) {
		    			System.out.println("ERROR: Failed to shutdown worker threads executor.");
		    		}
		    	} catch (InterruptedException e) {
		    		e.printStackTrace();
		    	}
		    	
		    	futuresMap.clear();
			}
									
		}
		
		@Override
		public void run() {
			super.run();			
			
			ExecutorService threadsDispatcherService = Executors.newSingleThreadExecutor();
			threadsDispatcherService.execute(new ThreadsDispatcher());
									
	    	/* Create the datagram socket used to read the incoming spikes. */
	    	
	    	socket = null;
	    	try {
	    		socket = new DatagramSocket(MNISTconst.APP_UDP_PORT);
	    		socket.setTrafficClass(UtilConst.IPTOS_THROUGHPUT);
	    	} catch (SocketException e) {
	    		e.printStackTrace();
	    	}
	    	assert socket != null;    	
	    	
	    	while (!shutdown) {
	    		// Receive the datagram packet with the latest spikes array.        		
        		DatagramPacket spikesPacket = null;
    			byte[] spikesBuffer = new byte[MNISTconst.MAX_DATA_BYTES];
        		try {
        			spikesPacket = new DatagramPacket(spikesBuffer, MNISTconst.MAX_DATA_BYTES);
        			socket.receive(spikesPacket);
        			spikesBuffer = spikesPacket.getData();
        		} catch (IOException e) {
        			System.out.println("spikesReceiver socket is closed");
        			break;
        		}
        		        		
        		// Create a new Runnable to do any kind of post-processing on the spikes sent
        		// by the terminal.
        		if (!isTrainingSession) {
					try {
						threadsDispatcherQueue.offer(new WorkerThread(spikesPacket, spikesBuffer), MNISTconst.DELTA_TIME / 2, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}               		
        		}
	    	}
	    	
	    	/* Shutdown executor. */
	    	
	    	threadsDispatcherService.shutdown();	    	
	    	try {
	    		boolean workerThreadsExecutorIsShutdown = threadsDispatcherService.awaitTermination(2, TimeUnit.SECONDS);
	    		if (!workerThreadsExecutorIsShutdown) {
	    			System.out.println("ERROR: Failed to shutdown threads dispatcher executor.");
	    		}
	    	} catch (InterruptedException e) {
	    		e.printStackTrace();
	    	}
	    	
	    	if (!socket.isClosed())
	    		socket.close();	    	
		}
		
	}
			
	boolean setSynapticWeights() {	
		final boolean STREAM_INTERRUPTED = false;	
		final boolean OPERATION_SUCCESSFUL = true;
		
		// Create a Terminal object holding all the info regarding this server,
		// which is the input sender. 
		thisApp.numOfNeurons = (short) MNISTconst.MAX_PIC_PIXELS;
		thisApp.numOfSynapses = Short.MAX_VALUE;
		thisApp.numOfDendrites = 0;
		thisApp.ip = Constants.USE_LOCAL_CONNECTION ? VirtualLayerManager.localIP : VirtualLayerManager.serverIP;
		thisApp.serverIP = thisApp.ip;
		thisApp.natPort = MNISTconst.APP_UDP_PORT;
		thisApp.id = thisApp.customHashCode();

		Random randomNumber = new Random();	
				
		MNISTmain.updateLogPanel("Weights update started", Color.BLACK);
		
		for (Node excNode : NodesManager.excNodes) {
			// Connect the excNode to the application. 
			excNode.terminal.postsynapticTerminals.add(thisApp);
			excNode.terminal.numOfDendrites -= MNISTconst.MAX_PIC_PIXELS;
			
			// Number of synapse per neuron that are effectively used.
			int activeSynPerNeuron = excNode.originalNumOfSynapses - excNode.terminal.numOfDendrites;
			
			// Array intended to store only the weights of the synapses that have been changed.
			byte[] sparseWeights;
			int sparseArrayLength = activeSynPerNeuron * excNode.terminal.numOfNeurons;
			sparseWeights = new byte[sparseArrayLength];
			
			// Float version of the previous array to be sent to the hash map storing on the server the weights of the nodes.
			float[] sparseWeightsFloat = new float[sparseArrayLength];
			
			// Array storing the flags which indicate whether the weight corresponding to the synapse should be updated during the training.
			byte[] updateWeightsFlags = new byte[sparseArrayLength];		
			
			// Size of each of the populations (assuming they are equal in size)
			int popSize = excNode.terminal.numOfNeurons / excNode.terminal.populations.size();
						
			for (int neuronIndex = 0; neuronIndex < excNode.terminal.numOfNeurons; neuronIndex++) {
				int weightOffset = 0; // Keep track of how many weights have been updated for a given connection.
				
				// To which population does the current neuron belong?
				int popIndex = neuronIndex / popSize;
				Population pop = excNode.terminal.popsMatrix[0][popIndex];				
				
				// Iterate over all the presynaptic connections of the population.
				for (Integer presynIndex : pop.inputIndexes) {
					Terminal presynapticTerminal = null;
					
					// Find the right presynaptic terminal from the input index of the population
					for (Terminal terminal : excNode.terminal.presynapticTerminals) 
						presynapticTerminal = presynIndex == terminal.id ? terminal : presynapticTerminal;
					
					assert presynapticTerminal != null;
	
					boolean presynTerminalIsApp = presynapticTerminal.equals(thisApp);
					boolean lateralConn = presynapticTerminal.equals(excNode.terminal);
					
					/*
					 * Depending on the nature of the connection (inhibitory or excitatory), there is a certain
					 * probability of establishing the connection. 
					 */		
					
					float probOfConnection = 1;
					float weightSign = 1;
					
					
					// Iterate over all the synapses coming from any given presynaptic connection.
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {												
						float random = randomNumber.nextFloat();
						
						// Flag that is set if the index of the presynaptic neuron is such that the neuron
						// could belong to the same population of the postsynaptic one
						boolean samePop = weightIndex >= popIndex * pop.numOfNeurons & 
								weightIndex < pop.numOfNeurons * (1 + popIndex);
						
						if (lateralConn & !samePop) { 
							weightSign = -1; 
							probOfConnection = 1.5f * 1 / MNISTconst.NUM_OF_LABELS; // 0.6 ?
						}
						else { 
							weightSign = 1; 
							probOfConnection = 1; 
							if (presynTerminalIsApp)
								probOfConnection = 1; // TODO: Think about this parameter
						}
						
						float weight = random < probOfConnection ? random : 0.0f;
						
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								weightSign * weight;
						
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] / UtilConst.MIN_WEIGHT);
	        			
						updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
	        					weightSign == -1 | lateralConn ? DONT_UPDATE_WEIGHT : UPDATE_WEIGHT;
					}
					
					weightOffset += presynapticTerminal.numOfNeurons;
				}
			}			
			
			VirtualLayerManager.weightsTable.put(excNode.id, sparseWeightsFloat);
			
			excNode.terminal.newWeights = sparseWeights;
			excNode.terminal.newWeightsIndexes = new int[] {0};
			excNode.terminal.updateWeightsFlags = updateWeightsFlags;
							
			VirtualLayerManager.unsyncNodes.add(excNode);			
		} 
		
		/* [End of for over excitatory nodes] */
		
		Future<Boolean> future = VirtualLayerManager.syncNodes();		
		
		// Wait for the synchronization process to be completed before proceeding.
		try {
			Boolean syncSuccessful = future.get();
			if (!syncSuccessful) {
				MNISTmain.updateLogPanel("Weights update interrupted", Color.RED);
				return STREAM_INTERRUPTED;
			}
		} catch (InterruptedException | ExecutionException e) {
			MNISTmain.updateLogPanel("Weights update interrupted", Color.RED);
			return STREAM_INTERRUPTED;
		}
		
		return OPERATION_SUCCESSFUL;
	}
	
	/**
	 * Method which set the flags of the excitatory nodes so that no further 
	 * learning takes place. 
	 * @return true if no error occurred, false is the sending of the updated 
	 * terminal info was interrupted. 
	 */
	
	boolean stopLearning() {
		final boolean STREAM_INTERRUPTED = false;
		final boolean OPERATION_SUCCESSFUL = true;
		
		for (Node excNode : NodesManager.excNodes) {
			int activeSynPerNeuron = excNode.originalNumOfSynapses - excNode.terminal.numOfDendrites;
			int sparseArrayLength = activeSynPerNeuron * excNode.terminal.numOfNeurons;
						
			excNode.terminal.updateWeightsFlags = new byte[sparseArrayLength];
			VirtualLayerManager.unsyncNodes.add(excNode);		
		}
		
		Future<Boolean> future = VirtualLayerManager.syncNodes();		
		try {
			Boolean syncSuccessful = future.get();
			if (!syncSuccessful) {
				MNISTmain.updateLogPanel("Weights reset interrupted", Color.RED);
				return STREAM_INTERRUPTED;
			}
		} catch (InterruptedException | ExecutionException e) {
			MNISTmain.updateLogPanel("Weights reset interrupted", Color.RED);
			return STREAM_INTERRUPTED;
		}
		
		return OPERATION_SUCCESSFUL;
	}
	
	boolean classifyInput(boolean isTrainingSession)  {	
		final boolean ERROR_OCCURRED = false;
		final boolean OPERATION_SUCCESSFUL = true;				
		analysisInterrupt = new AtomicBoolean(false);
		
		NetworkStimulator networkStimulator = new NetworkStimulator();		
		
		untaggedFiringRateMap = new ConcurrentHashMap<>(NodesManager.excNodes.size());	
		
		// Give the last terminal to be updated by setSynapticWeights a little bit of time to receive the package.
		if (isTrainingSession) {
			try {			
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				MNISTmain.updateLogPanel("Simulation interrupted while sleeping", Color.RED);
				return ERROR_OCCURRED;
			}		
		}
				
		MNISTmain.updateLogPanel("Analysis started", Color.BLACK);			        
       			
        /*
         * Send the retrieved grayscale candidates to the network.
         */
        
        GrayscaleCandidate[] inputCandidates = new GrayscaleCandidate[MNISTconst.NUM_OF_LABELS];
      
        // The nodes to be stimulated
        Node postSynNode = NodesManager.excNodes.get(0);
    	// Start the thread that handles the incoming spikes.
    	MuonTeacherSpikesReceiver spikesReceiver = new MuonTeacherSpikesReceiver(isTrainingSession);
    	spikesReceiver.start();      
    	
    	float rightGuess = 0.0f, totalGuess = 0.0f, deltaFactor = 1.0f;
    	float[] dummyInput = new float[MNISTconst.MAX_PIC_PIXELS];
    	Arrays.fill(dummyInput, 0.0f);
    	long postprocessingTime = 0; // Time take to post-process the firing rate vectors collected. 
    	GrayscaleCandidate dummyCandidate = // A Candidate object which contains a picture completely blank.
    			new GrayscaleCandidate(dummyInput, MNISTconst.UNDETERMINED);
    	
    	float[][] images = isTrainingSession ? MNISTmain.testSetImages : MNISTmain.trainingSetImages;
    	int[] labels = isTrainingSession ? MNISTmain.testSetLabels : MNISTmain.trainingSetLabels;    	   	
    	
    	for (int i = 0; i < images.length; i++) {
        	int allowedIterations = MNISTconst.MIN_ITERATIONS;
    		boolean sampleAnalysisFinished = false;  
    		
    		float[] imagePixels = images[i];
    		int imageLabel = labels[i];
    		
    		GrayscaleCandidate candidate = new GrayscaleCandidate(imagePixels, imageLabel);    		
    		/*
    		 * Prepare the inputs for this iteration. 
    		 * If this is the training phase, all nodes receive a blank input except for 
    		 * the population corresponding to the particle type of the current candidate.
    		 * 
    		 * If this is not the training session, all the nodes receive the same picture. 
    		 */
    		
    		if (isTrainingSession) {
    			Arrays.fill(inputCandidates, dummyCandidate);
    			inputCandidates[candidate.lablel] = candidate; 
    		} else {
    			Arrays.fill(inputCandidates, candidate);   
    		}
    		
    		for (Node excNode : NodesManager.excNodes) { 
    			// If this is not a training session create additional arrays for each node to store 
	    		// the firing rates of their neurons in response to the samples. 
    			if (!isTrainingSession) {
    				untaggedFiringRateMap.put(excNode.id, new float[excNode.terminal.numOfNeurons]);
    			}
    		}
    		    		
    		int iteration = 0, // Times the same input has been presented to the network. 
    				guessedClass = -1; 
    		double finalProbability = 0.0f; // Probability associated with the guessed class.    
    		double[] meanProbabilities = new double[MNISTconst.NUM_OF_LABELS]; // Temporary probs.
    		int[] meanSamples = new int[MNISTconst.NUM_OF_LABELS]; // Number of samples used to average the temp probs. 
        	long postprocessingStartTime = 0; // Time at which the post-processing start.    		    
    		
    		// Break the loop if the analysis has been interrupted or the application shutdown or 
    		// the sample has been thoroughly analyzed. 
        	while ( !analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished) {
        		currentInputClass = candidate.lablel;   		
        		iteration++; 
	        	
	        	long tmpTime = postprocessingStartTime != 0 ?  
	        		(System.nanoTime() - postprocessingStartTime) / UtilConst.MILLS_TO_NANO_FACTOR : 0;
	        	postprocessingTime = tmpTime < MNISTconst.DELTA_TIME ? MNISTconst.DELTA_TIME  : tmpTime;
        		
	        	float pauseLength = isTrainingSession ? 0 : MNISTconst.PAUSE_LENGTH;
	        	
	        	// Stimulate the input layers with the candidate grayscale map.
	        	// TODO: Handle disconnection of node during stimulation.
	        	Terminal[] terminals = new Terminal[MNISTmain.inputTerminals.size()];
	        	
        		ArrayList<Future<?>> inputSenderFutures = 
	        			networkStimulator.stimulateWithLuminanceMap(
	        					MNISTconst.STIMULATION_LENGTH, pauseLength, MNISTconst.DELTA_TIME, 
	        					new Node[] {postSynNode}, inputCandidates, MNISTmain.inputTerminals.toArray(terminals));  
	        	if (inputSenderFutures == null) {
	        		MNISTmain.updateLogPanel("Error occurred during the stimulation", Color.RED);
	        		return ERROR_OCCURRED;
	        	}        	
	        	  						        	
	        	boolean trainingDone = false, sampleClassified = false; // Flags that govern the flow. 
	        	
	        	/*
	        	 * Put this thread to sleep while the input is being sent but wake up before all the inputs
	        	 * have been sent so that there is still time to do a little bit of post-processing. 
	        	 */
	        	
	        	try {
	        		long timeout = (long)(MNISTconst.PAUSE_LENGTH + MNISTconst.STIMULATION_LENGTH) - postprocessingTime;
	        		if (timeout > 0)
	        			Thread.sleep(timeout);
				} catch (InterruptedException e) {
					MNISTmain.updateLogPanel("Stimulation interrupted during pause", Color.RED);
					return ERROR_OCCURRED;
				}  			
					        		        	
	        	postprocessingStartTime = System.nanoTime();
	        	
	        	if (!isTrainingSession & !shutdown) {
	        		// Get the firing rates vector of all the nodes
	        		float[][] untaggedFiringRates = new float[NodesManager.excNodes.size()][];
	        		for (int nodeIndex = 0; nodeIndex < NodesManager.excNodes.size(); nodeIndex++) {
	        			untaggedFiringRates[nodeIndex] = untaggedFiringRateMap.get(NodesManager.excNodes.get(nodeIndex).id);
	        		}
   					
	        		/*
	        		 * Compute which of the populations present the highest activity.
	        		 */
	        		
	        		int highestRateNodeNumber = 0;
    				double highestRateVectorLength = 0.0f, totalLength = 0.0f;    
    				
    				// This array store the lengths of the vectors whose elements are the firing rates of the neurons. There is
    				// a different vector, hence a different element of the array, for each of the labels
    				double[] vectorLengths = new double[MNISTconst.NUM_OF_LABELS];
    				
    				for (Node node : NodesManager.excNodes) {
    					int numOfNeurons = node.terminal.numOfNeurons;
    					int populationNeurons = numOfNeurons / MNISTconst.NUM_OF_LABELS; // It is assumed that the pops are equal in size
    		    					
    					// The index of the node that is being considered
    					int nodeIndex = NodesManager.excNodes.indexOf(node);
    					    			
    					// Iterate over the labels
    					for (int typeIndex = 0; typeIndex < MNISTconst.NUM_OF_LABELS; typeIndex++) {
    						
    						// Iterate over the neurons of the population corresponding to the typeIndex label
    						for (int neuronIndex = 0; neuronIndex < populationNeurons; neuronIndex++) {
    							vectorLengths[typeIndex] += 
    									Math.pow(untaggedFiringRates[nodeIndex][typeIndex * populationNeurons + neuronIndex], 2); 
    						}
    						
    						vectorLengths[typeIndex] = Math.sqrt(vectorLengths[typeIndex]);
    						totalLength += vectorLengths[typeIndex];
    						if (vectorLengths[typeIndex] > highestRateVectorLength) {
    							highestRateVectorLength = vectorLengths[typeIndex];
    							highestRateNodeNumber = typeIndex;
    						}
    					}
    				}
    				
					// The probability with which the guess has been made.     				
    				//meanProbabilities[highestRateNodeNumber] += highestRateVectorLength / totalLength;
    				
    				double averageLength = (totalLength - highestRateVectorLength) / (MNISTconst.NUM_OF_LABELS - 1);
    				
    				meanProbabilities[highestRateNodeNumber] += 1 - averageLength / highestRateVectorLength;
    				
    				meanSamples[highestRateNodeNumber]++; // How many times the same class has been associated with the input. 
					
    				/*
    				 * Compute which class best describes the current input and the probability related
    				 * to the guess. 
    				 */
    				
    				if (iteration >= allowedIterations) {
    					double maxProbability = meanProbabilities[0];
    					int tentativeClass = 1;
    					
    					// Compute which probability is the higher among the different classes. 
    					for (int typeIndex = 0; typeIndex < MNISTconst.NUM_OF_LABELS; typeIndex++) {
    						if (meanProbabilities[typeIndex] >= maxProbability) {
    							maxProbability = meanProbabilities[typeIndex];
    							tentativeClass = typeIndex;
    						}
    					}
    					
    					maxProbability /= meanSamples[tentativeClass];
    					    					
    					if (maxProbability > 0.6f | allowedIterations >= MNISTconst.MAX_ITERATIONS) {
    						sampleClassified = true;
	    					finalProbability = maxProbability;
	    					guessedClass = tentativeClass;
    					} else {
    						allowedIterations += MNISTconst.ITERATION_INCREMENT;
    					}    					
    				}
    				
    				System.out.println("iteration " + iteration + " label " + i);
	        	} else if (!shutdown) {			        	
		        	trainingDone = (currentInputClass != MNISTconst.UNDETERMINED & iteration == 1);
		        		        			        			        	
		        	System.out.println("Class " + currentInputClass + " label " + i);   
	        	}	 
	        		        	
	        	// Wait for all the InputSender threads to finish by retrieving their Future objects.		
	    		try {
	    			for (Future<?> inputSenderFuture : inputSenderFutures)
	    				inputSenderFuture.get();
	    		} catch (InterruptedException | ExecutionException e) {
	    			e.printStackTrace();
	    			return false;
	    		}		        	
	        	
	        	sampleAnalysisFinished = sampleClassified | trainingDone;
    		}
        	/* [End of while ( !analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished)] */
        	
        	totalGuess++;
        	//guessedClass = guessedClass == 0 ? 1 : 3; // TODO: Make function that convert tag in type class.
        	if (guessedClass == currentInputClass) {
        		rightGuess++;        		
        	}
        	
        	if (!isTrainingSession)
        		System.out.println("Real class: " + candidate.lablel + " Tentative class: " + guessedClass 
        				+ " finalProbability " + finalProbability + " Success rate: " + (rightGuess / totalGuess));
        }   
    	
    	/* Shutdown operations */    	  
    	
    	// Shutdown worker threads
    	boolean terminationSuccessful = true;
    	
    	spikesReceiver.shutdown = true;
    	if (spikesReceiver.socket != null)
    		spikesReceiver.socket.close();
    	try {
    		spikesReceiver.join(100);
    	} catch (InterruptedException e) {
    		terminationSuccessful = false;
			MNISTmain.updateLogPanel("spikesReceiver shutdown interrupted", Color.RED);
    	}
    	
    	networkStimulator.inputSenderService.shutdown();
    	try {
			terminationSuccessful &= networkStimulator.inputSenderService.awaitTermination(100, TimeUnit.MILLISECONDS);
			if (!terminationSuccessful) {
				MNISTmain.updateLogPanel("inputSenderService didn't shutdown in time", Color.RED);
			}
		} catch (InterruptedException e) {
			terminationSuccessful = false;
			MNISTmain.updateLogPanel("inputSenderService shutdown interrupted", Color.RED);
		}
    	
    	// Clear hash maps.
    	Collection<DatagramSocket> socketsCollection = networkStimulator.socketsHashMap.values();
    	for (DatagramSocket oldSocket : socketsCollection) {
    		oldSocket.close();
    	}
    	networkStimulator.socketsHashMap.clear();    	
    	untaggedFiringRateMap.clear();   
          
		return terminationSuccessful;				
	}
	
}
