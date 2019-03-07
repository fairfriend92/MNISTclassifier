package mnist_classifier_package;
import java.awt.Color;
import overmind_server.*;
import overmind_utilities.GrayscaleCandidate;
import overmind_utilities.NetworkStimulator;
import overmind_utilities.NodesManager;
import overmind_utilities.PicturePanel;
import overmind_utilities.UtilConst;

import java.awt.List;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
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
		
	// Object used to synchronize the worker thread of SpikesReceiver with thread on which 
	// NetworkTrainer runs
	private final static Object lock = new Object();
	
	// Constants local to this class.
	private final byte UPDATE_WEIGHT = 1;
	private final byte DONT_UPDATE_WEIGHT = 0;
	
	// Flags that control the execution of the code
	static boolean shutdown = false;
	static AtomicBoolean analysisInterrupt = new AtomicBoolean(false);
					
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
	
		Random randomNumber = new Random();	
				
		MNISTmain.updateLogPanel("Weights update started", Color.BLACK);
		
		for (Node excNode : NodesManager.excNodes) {
			// Number of synapse per neuron that are effectively used.
			int activeSynPerNeuron = excNode.originalNumOfSynapses - excNode.terminal.numOfDendrites;
						
			int arrayLength = activeSynPerNeuron * excNode.terminal.numOfNeurons;
			byte[] weights = new byte[arrayLength];
			
			// Float version of the previous array to be sent to the hash map storing on the server the weights of the nodes.
			float[] weightsFloat = new float[arrayLength];
			
			// Array storing the flags which indicate whether the weight corresponding to the synapse should be updated during the training.
			byte[] updateWeightsFlags = new byte[arrayLength];		
			
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
	
					boolean lateralConn = presynapticTerminal.equals(excNode.terminal);
					
					/*
					 * Depending on the nature of the connection (inhibitory or excitatory), there is a certain
					 * probability of establishing the connection. 
					 */		
					
					float probOfConnection = 1;
					float weightSign = 1;
										
					// Iterate over all the synapses8 coming from any given presynaptic connection.
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {	
						
						//*
						float random = (float)randomNumber.nextGaussian() * 0.00f + 0.1f;
						random = random > MNISTconst.MAX_WEIGHT ? MNISTconst.MAX_WEIGHT : random;
						random = random < MNISTconst.MIN_WEIGHT ? MNISTconst.MIN_WEIGHT : random;
						/*/
												
						/*
						float random = randomNumber.nextFloat();
						 */
						
						// Flag that is set if the index of the presynaptic neuron is such that the neuron
						// could belong to the same population of the postsynaptic one
						boolean samePop = weightIndex >= popIndex * pop.numOfNeurons & 
								weightIndex < pop.numOfNeurons * (1 + popIndex);
						
						if (lateralConn & !samePop) { 
							weightSign = -1; 
							probOfConnection = 0.0f; 
						} else { 
							weightSign = 1; 
							probOfConnection = lateralConn ? 0.0f : 1.0f; 	 
						}
						
						float weight = random <= probOfConnection ? random : 0.0f;
						
						weightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								weightSign * weight;
						
						weights[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								(byte)(weightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] / UtilConst.MIN_WEIGHT);
	        			
						updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
	        					weightSign == -1 | lateralConn ? DONT_UPDATE_WEIGHT : UPDATE_WEIGHT;						
					}
					
					weightOffset += presynapticTerminal.numOfNeurons;
				}
			}			
			
			VirtualLayerManager.weightsTable.put(excNode.id, weightsFloat);
			
			excNode.terminal.newWeights = weights;
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
	 * @return true if no error occurred, false is the sending of the updated 
	 * terminal info was interrupted. 
	 */
	
	boolean stopLearning() {
		final boolean STREAM_INTERRUPTED = false;
		final boolean OPERATION_SUCCESSFUL = true;
		
		Random randomNumber = new Random();	
		
		for (Node excNode : NodesManager.excNodes) {
			int activeSynPerNeuron = excNode.originalNumOfSynapses - excNode.terminal.numOfDendrites;
			int weightsArrayLength = activeSynPerNeuron * excNode.terminal.numOfNeurons;
			
			int sparseArrayLength = excNode.terminal.numOfNeurons * excNode.terminal.numOfNeurons;
			
			byte[] sparseWeights = new byte[sparseArrayLength];
			int[] sparseWeightsIdxs = new int[sparseArrayLength];	
			
			// Index used to access both the arrays and that needs to be incremented manually
			int arrayIdx = 0;
			
			int popSize = excNode.terminal.numOfNeurons / excNode.terminal.populations.size();
						
			for (int neuronIndex = 0; neuronIndex < excNode.terminal.numOfNeurons; neuronIndex++) {
				int weightOffset = 0; 
				
				int popIndex = neuronIndex / popSize;
				Population pop = excNode.terminal.popsMatrix[0][popIndex];				
				
				for (Integer presynIndex : pop.inputIndexes) {
					Terminal presynapticTerminal = null;
					
					for (Terminal terminal : excNode.terminal.presynapticTerminals) 
						presynapticTerminal = presynIndex == terminal.id ? terminal : presynapticTerminal;
					
					assert presynapticTerminal != null;
	
					boolean lateralConn = presynapticTerminal.equals(excNode.terminal);
	
					// We need to change the weights only of the synapses of the lateral connections					
					if (lateralConn) {
								
						float probOfConnection = 1;
						float weightSign = 1;
						float weight = 1;
											
						for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {	
							//*
							float randomExc = (float)randomNumber.nextGaussian() * 0.5f + 0.5f;
							randomExc = randomExc > MNISTconst.MAX_WEIGHT ? MNISTconst.MAX_WEIGHT : randomExc;
							randomExc = randomExc < MNISTconst.MIN_WEIGHT ? MNISTconst.MIN_WEIGHT : randomExc;
							
							float randomInh = (float)randomNumber.nextGaussian() * 0.5f + 0.5f;
							randomInh = randomInh > MNISTconst.MAX_WEIGHT ? MNISTconst.MAX_WEIGHT : randomInh;
							randomInh = randomInh < MNISTconst.MIN_WEIGHT ? MNISTconst.MIN_WEIGHT : randomInh;
							/*/
							
							/*
							float randomExc = randomNumber.nextFloat(), 
									randomInh = randomExc;
							/ */
														
							boolean samePop = weightIndex >= popIndex * pop.numOfNeurons & 
									weightIndex < pop.numOfNeurons * (1 + popIndex);
														
							if (!samePop) { 
								weightSign = -1; 
								probOfConnection = 0.0f; 
								weight = randomInh <= probOfConnection ? randomInh : 0.0f;
							}
							else { 
								weightSign = 1; 
								probOfConnection = 0.0f; 
								weight = randomExc <= probOfConnection ? randomExc : 0.0f;
							}							
							
							sparseWeights[arrayIdx] = 
									(byte)(weightSign * weight / UtilConst.MIN_WEIGHT);							
							
							sparseWeightsIdxs[arrayIdx] = neuronIndex * activeSynPerNeuron + weightIndex + weightOffset;
							
							arrayIdx++;
						}
					}
					
					weightOffset += presynapticTerminal.numOfNeurons;
				}
			}	
			
			excNode.terminal.newWeights = sparseWeights;
			excNode.terminal.newWeightsIndexes = sparseWeightsIdxs;
			excNode.terminal.updateWeightsFlags = new byte[weightsArrayLength];
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
		try {			
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			MNISTmain.updateLogPanel("Simulation interrupted while sleeping", Color.RED);
			return ERROR_OCCURRED;
		}	
				
		MNISTmain.updateLogPanel("Analysis started", Color.BLACK);			        
       			
        /*
         * Send the retrieved gray-scale candidates to the network.
         */
        
        GrayscaleCandidate[] inputCandidates = new GrayscaleCandidate[MNISTconst.NUM_OF_LABELS];
        
        // The nodes to be stimulated
        Node postSynNode = NodesManager.excNodes.get(0);
    	// Start the thread that handles the incoming spikes.
    	MuonTeacherSpikesReceiver spikesReceiver = new MuonTeacherSpikesReceiver(isTrainingSession);
    	spikesReceiver.start();      
    	
    	float deltaFactor = 1.0f;
    	int rightGuess = 0, totalGuess = 0;
    	
    	// Rare digits MNIST related.
    	int falsePositive = 0, falseNegative = 0;
    	
    	float[] dummyInput = new float[MNISTconst.MAX_PIC_PIXELS];
    	Arrays.fill(dummyInput, 0.0f);
    	long postprocessingTime = 0; // Time take to post-process the firing rate vectors collected. 
    	GrayscaleCandidate dummyCandidate = // A Candidate object which contains a picture completely blank.
    			new GrayscaleCandidate(dummyInput, MNISTconst.UNDETERMINED);
    	
    	ArrayList<float[]> images = !isTrainingSession ? MNISTmain.testSetImages : MNISTmain.trainingSetImages;
    	ArrayList<Integer> labels = !isTrainingSession ? MNISTmain.testSetLabels : MNISTmain.trainingSetLabels;    	
    	
    	if (images == null | labels == null) {
    		MNISTmain.updateLogPanel("Dataset was not loaded", Color.RED);
    		return ERROR_OCCURRED;
    	}
    	
    	// During the training phase the images are ordered using their labels. These arrays
    	// are meant to hold the images and the labels in the correct order
    	ArrayList<float[]> orderedImgs = new ArrayList<>();
    	ArrayList<Integer> orderedLbls = new ArrayList<>();
    	
    	if (isTrainingSession) {        		
        	Iterator<Integer> labelIter = labels.iterator();
        	Iterator<float[]> imgIter = images.iterator();
    		
	    	// Iterate until all the images have been considered
	    	while (labels.size() > 0) {
	    		
	        	// What is the label that should be put in the array next?
	        	int labelNeeded = 0;
	        	
	        	// Iterate until all the images have been put in the right places
	        	while (labelNeeded < MNISTconst.NUM_OF_LABELS) {
	        		
	        		/*
	        		 * When we are detecting rare events, we don't want to include the 
	        		 * rare digit in the training dataset.
	        		 */
	        		
	        		if (MNISTmain.rareDigit != null && MNISTmain.rareDigit == labelNeeded) {
	        			labelNeeded++;
		    			orderedLbls.add(MNISTmain.rareDigit);		    			
		    			orderedImgs.add(dummyInput);
	        		}
	        		
	        		/*
	        		 * There's a chance that the images might not be equally distributed; therefore
	        		 * the last set of ordered images could be incomplete. This is known once the iterator
	        		 * has reached the end of the array and this exception is thrown.
	        		 */
	        		
	        		try {
		        		Integer label = labelIter.next();
		        		float[] img = imgIter.next();
		        		
		        		// If the current image is exactly the one that was needed
			    		if (labelNeeded == label.intValue()) {
			    			// For the next iteration look for a different label
			    			labelNeeded++;
			    			
			    			orderedLbls.add(label);
			    			orderedImgs.add(img);
			    			
			    			labelIter.remove();
			    			imgIter.remove();
			    			
			    			// We may have passed the label that we are looking for,
			    			// therefore restart the iteration
			    			labelIter = labels.iterator();
			        		imgIter = images.iterator();
			    		}
	        		} catch (NoSuchElementException e) {
	        			/*
	        			labelNeeded++;	        	
	        			
	        			orderedLbls.add(MNISTconst.UNDETERMINED);
	        			orderedImgs.add(dummyInput);
	        			
	        			labelIter = labels.iterator();
		        		imgIter = images.iterator();
		        		*/
	        			
	        			labels.clear();
	        			images.clear();
	        			break;
	        		}          
	        		
	        	}
	        	/* [End of inner while()] */
	        }
	    	/* [End of outer while()] */
    	}
    	
    	// Step with which the index should be incremented
    	int dI = 1; 
    	
    	// During the training phase use the ordered images and consider 10 images for each iteration 
    	if (isTrainingSession) {images = orderedImgs; labels = orderedLbls; dI = 10;}   	
    	
    	// Stimulate the input layers with the candidate grayscale map.
    	// TODO: Handle disconnection of node during stimulation.
    	Terminal[] terminals = new Terminal[MNISTmain.inputTerminals.size()];
    	
    	float pauseLength = isTrainingSession ? 0 : MNISTconst.PAUSE_LENGTH;   
    	float stimulationLength = isTrainingSession ? 5 * MNISTconst.DELTA_TIME : MNISTconst.STIMULATION_LENGTH;
    	//float pauseLength = MNISTconst.PAUSE_LENGTH;  
    	//float stimulationLength = MNISTconst.STIMULATION_LENGTH;
    	
    	for (int i = 0; i < images.size(); i+=dI) {
        	int allowedIterations = MNISTconst.MIN_ITERATIONS;
    		boolean sampleAnalysisFinished = false;      		   		
    		
			GrayscaleCandidate candidate = null;
    		
			// Find the candidate pictures that should be sent
    		for (int j = 0; j < dI; j++) {
    			
    			try {
	    			float[] imagePixels = images.get(i + j);
	        		int imageLabel = labels.get(i + j).intValue();
	        		candidate = new GrayscaleCandidate(imagePixels, imageLabel);  
    			} catch (IndexOutOfBoundsException e) {
    				
    				// If the end of the array has been reached use the dummy input for the 
    				// populations missing an input
    				candidate = dummyCandidate; // TODO: This should not happen in theory   			
    			}
        		
    			assert candidate != null;
    			
        		inputCandidates[j] = candidate;
    		}    		
    	    		
    		if (!isTrainingSession) {
    			MNISTmain.updatePictureFrame(candidate.grayscalePixels, MNISTconst.PIC_SIDE, 
    					MNISTconst.PIC_SIDE, 10, new String("" + candidate.label));   			
    			Arrays.fill(inputCandidates, candidate);   
    		}
    		
    		for (Node excNode : NodesManager.excNodes) { 
    			// If this is not a training session create additional arrays for each node to store 
	    		// the firing rates of their neurons in response to the samples. 
    			if (!isTrainingSession) {
    				untaggedFiringRateMap.put(excNode.id, new float[excNode.terminal.numOfNeurons]);
    			}
    		}
    		    	
    		// How many times the same input has been sent to the network.
    		int iteration = 0; 
    		
    		// The guessed label for the current input.
    		int guessedLabel = -1; 
    		
    		// Probability associated with the guess.
    		double finalProbability = 0.0f; 
    		double normalDigitProbability = 0.0f;
    		
    		// The guessed label for this iteration of the algorithm. 
    		int tentativeLabel = 0;
    		
    		// The probabilities that a given the label is correct one.
			double[] probabilities = new double[MNISTconst.NUM_OF_LABELS];
    		
        	long postprocessingStartTime = 0; 
        	int currentInputClass = MNISTconst.UNDETERMINED;    		
        	
    		// Break the loop if the analysis has been interrupted or the application shutdown or 
    		// the sample has been thoroughly analyzed. 
        	while (!analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished) {
	        	long tmpTime = postprocessingStartTime != 0 ?  
	        		(System.nanoTime() - postprocessingStartTime) / UtilConst.MILLS_TO_NANO_FACTOR : 0;
	        	postprocessingTime = tmpTime < MNISTconst.DELTA_TIME ? MNISTconst.DELTA_TIME  : tmpTime;       
	        	
        		ArrayList<Future<?>> inputSenderFutures = 
	        			networkStimulator.stimulateWithLuminanceMap(
	        					stimulationLength, 0, MNISTconst.DELTA_TIME, 
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
	        		long timeout = (long)(stimulationLength) - postprocessingTime;
	        		if (timeout > 0)
	        			Thread.sleep(timeout);
				} catch (InterruptedException e) {
					MNISTmain.updateLogPanel("Stimulation interrupted during pause", Color.RED);
					return ERROR_OCCURRED;
				}  			
					        		        	
	        	postprocessingStartTime = System.nanoTime();
	        	
	        	if (!isTrainingSession & !shutdown) {
	        		currentInputClass = candidate.label;   		
	        		
	        		// Get the firing rates vector of all the nodes
	        		float[][] untaggedFiringRates = new float[NodesManager.excNodes.size()][];
	        		for (int nodeIndex = 0; nodeIndex < NodesManager.excNodes.size(); nodeIndex++) {
	        			untaggedFiringRates[nodeIndex] = untaggedFiringRateMap.get(NodesManager.excNodes.get(nodeIndex).id);
	        		}
   						        		
	        		/* Compute which of the populations present the highest activity */
    				
    				// Each population rate is given by the sum of the firing rates of its neurons.
    				double[] populationRates = new double[MNISTconst.NUM_OF_LABELS];
    				double maxPopulationRate = 0.0f, totalPopulationRate = 0.0f;  
    					
    				for (Node node : NodesManager.excNodes) {
    					int numOfNeurons = node.terminal.numOfNeurons;
    					
    					// It is assumed that the populations are equal in size.
    					int populationNeurons = numOfNeurons / MNISTconst.NUM_OF_LABELS; 
    		    					
    					// The index of the node that is being considered
    					int nodeIndex = NodesManager.excNodes.indexOf(node);
    					    			
    					// Iterate over the labels
    					for (int label = 0; label < MNISTconst.NUM_OF_LABELS; label++) {
    						
    						// Iterate over the neurons of the population corresponding to the label
    						for (int neuron = 0; neuron < populationNeurons; neuron++) {
    							populationRates[label] += 
    									untaggedFiringRates[nodeIndex][label * populationNeurons + neuron]; 
    						}
    						
    						totalPopulationRate += populationRates[label];
    						if (populationRates[label] > maxPopulationRate) {
    							maxPopulationRate = populationRates[label];
    							tentativeLabel = label;
    						}
    					}
    				}   	    	

    				probabilities[tentativeLabel] += populationRates[tentativeLabel] / totalPopulationRate;
    									
    				/*
    				 * Compute which class best describes the current input and the probability related
    				 * to the guess. 
    				 */
    				
    				// Allowed iteration is subtracted by 1 because we start counting iterations from 0.
    				if (iteration >= allowedIterations - 1) {
    					guessedLabel = 0;   	
    					double maxProbability = 0.0f;
    					
    					for (int label = 0; label < MNISTconst.NUM_OF_LABELS; label++) {
    						if (probabilities[label] > maxProbability) {
    							maxProbability = probabilities[label];
    							guessedLabel = label;
    						}
    					}
    					
    					maxProbability /= allowedIterations;
    					
    					if (maxProbability > 0.6f | allowedIterations >= MNISTconst.MAX_ITERATIONS) {
    						sampleClassified = true;
	    					finalProbability = maxProbability;	
	    					normalDigitProbability = finalProbability * totalPopulationRate;
    					} else {    						
    						allowedIterations += MNISTconst.ITERATION_INCREMENT;
    					}    					
    				}
    				
    				iteration++; 
    				System.out.println("iteration " + iteration + " label " + i);
	        	} else if (!shutdown) {			        	
		        	trainingDone = true; // Training is finished as soon as the batch of samples has been sent
		        		        			        			        	
		        	System.out.println("Remaining images " + (images.size() - i));   
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
        	
        	/* Compute the performance of the network and print the results */
        	
        	if (!isTrainingSession & !shutdown) {        		
        		// If the classic MNIST is being used:
        		if (MNISTmain.rareDigit == null) {	        		
	            	if (guessedLabel == currentInputClass) {
	            		rightGuess++;    
	            	}      
	            	
	            	System.out.println("Real class: " + candidate.label + " Tentative class: " + guessedLabel 
	        				+ " finalProbability " + finalProbability + " Success rate: " + (rightGuess / (i + 1)));
        		} else 
        		// If the rare digits MNIST is being used:
        		{
        			// TODO: The probability is not normalized and can be greater than 1.0f;
        			boolean isRareDigit = normalDigitProbability < 0.05f;
        			
        			if (candidate.label == MNISTmain.rareDigit) {
        				totalGuess++;
        				if (isRareDigit) {
        					System.out.println("Right guess");
        					rightGuess++;
        				} else {
        					System.out.println("False negative");
        					falseNegative++;
        				}
        			} else {
        				if (isRareDigit) {
        					System.out.println("False positive");
        					falsePositive++;
        				}
        			}
        			
        			// Until a rare digit appears, accuracy is set to 1.0f to prevent division by zero. 
        			float accuracy = totalGuess == 0 ? 1.0f : (float) rightGuess / totalGuess;
        			
        			DecimalFormat decimalFormat = new DecimalFormat("#.00");        			
        			System.out.println("Probability: " + decimalFormat.format(normalDigitProbability) 
        			+ " Accuracy: " + accuracy 
        			+ " False positive: " + ((float)falsePositive / (i + 1)) 
        			+ " False negative " + ((float)falseNegative / (i + 1)));
        		}
        	}
        	
        	if (!shutdown) {
            	/*
        		 * During the classification send a dummy input for time pauseLength to allow the 
        		 * potential of the neurons to go to rest
        		 */
        		
        		Arrays.fill(inputCandidates, dummyCandidate);
        		
        		ArrayList<Future<?>> inputSenderFutures = 
	        			networkStimulator.stimulateWithLuminanceMap(
	        					0, pauseLength, MNISTconst.DELTA_TIME, 
	        					new Node[] {postSynNode}, inputCandidates, MNISTmain.inputTerminals.toArray(terminals));  
	        	if (inputSenderFutures == null) {
	        		MNISTmain.updateLogPanel("Error occurred during the stimulation", Color.RED);
	        		return ERROR_OCCURRED;
	        	}     
	        	
	        	try {
	        		long timeout = (long)(pauseLength);
	        		if (timeout > 0)
	        			Thread.sleep(timeout);
				} catch (InterruptedException e) {
					MNISTmain.updateLogPanel("Stimulation interrupted during pause", Color.RED);
					return ERROR_OCCURRED;
				}  	            		        	
        	}
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
			terminationSuccessful &= networkStimulator.inputSenderService.awaitTermination(
					(long)(stimulationLength + pauseLength), TimeUnit.MILLISECONDS);
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
