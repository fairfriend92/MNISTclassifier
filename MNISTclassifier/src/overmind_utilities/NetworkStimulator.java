package overmind_utilities;

import overmind_server.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.*;

import com.example.overmind.Terminal;

/**
 * Class that contains methods to send an input to one or more layers of the
 * neural network.
 * @author rodolfo
 */

public class NetworkStimulator {
	
	private SpikeInputCreator spikeInputCreator = new SpikeInputCreator();
	
	/*
	 * When the class is instantiated create an input array from a picture containing
	 * only noise and no trace. 
	 */
	
	public NetworkStimulator() {
		
	}
	
	// Hash map used to store the sockets which send the inputs to the terminals.
	public static ConcurrentHashMap<Integer, DatagramSocket> socketsHashMap = null;
	
	// Create a service for the threads that send the inputs to the respectinve input layers.
	public ExecutorService inputSenderService = null;
	
	/**
	 * For each different input create a new terminal.
	 * 
	 * @param numOfInputs How many different classes of inputs are to be sent
	 * @param numOfPixels How many pixels each stimulus is made of
	 * @return An ArrayList of terminals, one for each different input
	 */
		
	public static ArrayList<Terminal> createInputTerminals (int numOfInputs, int numOfPixels) {
		if (numOfInputs == 0 | numOfPixels == 0) { 
			System.out.println("createInputTerminals: One or more of the parameters has a zero value");
			return null; 
		}
		
		if (socketsHashMap == null) { socketsHashMap = new ConcurrentHashMap<>(numOfInputs); }
		
		ArrayList<Terminal> inputTerminals = new ArrayList<>(numOfInputs);
		int natPort = 4199;
		
		for (int i = 0; i < numOfInputs; i++) {
			Terminal terminal = new Terminal();
			terminal.numOfNeurons = (short) numOfPixels;
			terminal.numOfDendrites = 0; // This is not important
			terminal.numOfSynapses = Short.MAX_VALUE; // The input terminal can be connected with as many populations as possible
			terminal.ip = Constants.USE_LOCAL_CONNECTION ? VirtualLayerManager.localIP : VirtualLayerManager.serverIP;
						
			try {
				DatagramSocket outputSocket = new DatagramSocket(natPort);
				outputSocket.setTrafficClass(UtilConst.IPTOS_THROUGHPUT);   
				terminal.natPort = natPort;
				//System.out.println("natPort " + terminal.natPort);
				terminal.id = terminal.customHashCode();
				socketsHashMap.put(Integer.valueOf(terminal.id), outputSocket);
			} catch (SocketException e) {
				e.printStackTrace();
				return null;
			}
			
			inputTerminals.add(terminal);	
			natPort++;
		}
		
		return inputTerminals;
	}
	
	/**
	 * Send a luminance map as an input to the chosen nodes. 
	 * Launch a separate thread for each input terminal. Then wait 
	 * for the threads to finish their jobs before returning.  
	 * 
	 * @param postSynNodes The nodes to be stimulated
	 * @param inputs A collection of stimuli
	 * @param inputTerminals The terminals from which the inputs should be sent
	 */
	
	public ArrayList<Future<?>> stimulateWithLuminanceMap(float stimulationLength, float pauseLength, int deltaTime, 
			Node[] postSynNodes, GrayscaleCandidate[] inputs, Terminal[] inputTerminals) {	
		
		if (inputTerminals == null) { 
			System.out.println("stimulateWithLuminanceMap: inputTerminals is null"); 
		}
		else if (inputTerminals.length != inputs.length) { 
			System.out.println("stimulateWithLuminanceMap: inputTerminals length is different from inputs length"); 
		}
		
		if (socketsHashMap == null | inputSenderService == null) {
			socketsHashMap = new ConcurrentHashMap<>(inputTerminals.length);
			inputSenderService = Executors.newFixedThreadPool(inputTerminals.length);
		}		
				
		// List of future objects used to signal when an inputSender thread is done.
		ArrayList<Future<?>> inputSenderFutures = new ArrayList<Future<?>>(inputs.length);
		
		for (int index = 0; index < inputs.length; index++) {
			
			// Socket used to send the input to the node. 
			DatagramSocket outputSocket = socketsHashMap.get(inputTerminals[index].id);

			// If necessary create the socket and put it in the hash map.
			if (outputSocket == null) {
				try {
					outputSocket = new DatagramSocket();
					outputSocket.setTrafficClass(UtilConst.IPTOS_THROUGHPUT);   
				} catch (SocketException e) {
					e.printStackTrace();
				}
				socketsHashMap.put(inputTerminals[index].id, outputSocket);
			}
			
			for (Node postSynNode : postSynNodes) {
				if (postSynNode.terminal.presynapticTerminals.contains(inputTerminals[index])) {
					Future<?> inputSenderFuture = 
							inputSenderService.submit(new InputSender(stimulationLength, pauseLength, deltaTime, 
									postSynNode, inputs[index], outputSocket));

					inputSenderFutures.add(inputSenderFuture);
				}
			}
			
		}			
		
		return inputSenderFutures;
	}
	
	/**	 
	 * The luminance map is first converted in a spike train whose length in units 
	 * of time is determined by the length of the stimulation process and by the size of the bins. 
	 * Then a new sample of the spike train is sent every deltaTime ms. 
	 */
	
	public class InputSender implements Runnable {
		private int stimulationIterations, // How many times should the input be sent to the network?
		pauseIterations; // How many times should the dummy input be sent?
		private Node postSynNode;
		private GrayscaleCandidate input;
		private int deltaTime;
		private DatagramSocket outputSocket;
		
		InputSender(float stimulationLength, float pauseLength, int deltaTime, Node postSynNode, GrayscaleCandidate input, DatagramSocket outputSocket) {
			this.deltaTime = deltaTime;
			stimulationIterations = (int)(stimulationLength / deltaTime);
			pauseIterations = (int)(pauseLength / deltaTime);
			this.postSynNode = postSynNode;
			this.input = input;
			this.outputSocket = outputSocket;
		}
		
		@Override
		public void run() {							
	        // Address and nat port of the terminal to which the input should be sent.
	        InetAddress inetAddress = null;
			try {
				inetAddress = InetAddress.getByName(postSynNode.terminal.ip);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
			assert inetAddress != null;
	        int natPort = postSynNode.terminal.natPort;
	        
	        // Send a new input, Poisson distributed, to the node every deltaTime
	        // for a total of numOfIterations times. 	        	        
			for (int index = 0; index < stimulationIterations + pauseIterations; index++) {
				long startingTime = System.nanoTime();				
				
				byte[] spikeInput = index < stimulationIterations ? 
						spikeInputCreator.createFromLuminance(input.grayscalePixels, index == 0) : 
							spikeInputCreator.createFromLuminance(new float[input.grayscalePixels.length], false);
						
				try {
					DatagramPacket spikeInputPacket = new DatagramPacket(spikeInput, spikeInput.length, inetAddress, natPort);
					outputSocket.send(spikeInputPacket);
				} catch (IOException e) {
					e.printStackTrace();
				}		
				
				long timeout = deltaTime - (System.nanoTime() - startingTime) / UtilConst.MILLS_TO_NANO_FACTOR;
				
				if (timeout > 0) {				
					try {
						Thread.sleep(timeout);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}			
		}
	}

}
