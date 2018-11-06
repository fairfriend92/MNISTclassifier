package mnist_classifier_package;

public class MNISTconst {	 
	/* Labels */	
	static final int NUM_OF_LABELS = 10;
	static final int UNDETERMINED = -1, ZERO = 0, ONE = 1, TWO = 2, THREE = 3, FOUR = 4, FIVE = 5,
			SIX = 6, SEVEN = 7, EIGHT = 8, NINE = 9;	
	    
    /* Application constants */
    static final int DELTA_TIME = 30; 
    static final int STIMULATION_LENGTH_SIM_TIME = 100; // Length in simulation time of the stimulation period. 
    static final int PAUSE_LENGTH_SIM_TIME = 0;
    static final float DELTA_TIME_SIM = 0.5f;
    static final float STIMULATION_LENGTH = 
    		STIMULATION_LENGTH_SIM_TIME * DELTA_TIME / DELTA_TIME_SIM; // Length in ms (real time) of the period during which the inputs are presented to the network.
    static final float PAUSE_LENGTH = 
    		PAUSE_LENGTH_SIM_TIME * DELTA_TIME / DELTA_TIME_SIM; 
    static final short MAX_PIC_PIXELS = 784; // The maximum number of pixels a sample image can be made of.
    static final float MEAN_RATE_INCREMENT = 0.01f; // Inverse of the number of samples need to compute the mean firing rate.
    static final int MIN_ITERATIONS = 4; // Minimum number of times the same input can be presented to the network during learning. 
    static final int MAX_ITERATIONS = 8;
    static final int ITERATION_INCREMENT = 2;
    static final float MAX_FACTOR = 1.5f; // Maximum multiplicative factor of the threshold for the delta between the firing rate vectors. 
    static final float MIN_FACTOR = 0.5f;
    static final float BASE_DELTA = 0.15f; // Default multiplicative factor.
    static final float EXC_TO_INH_PERCT = 0.8f;
    static final float INH_TO_EXC_PERCT = 0.6f;
    
    /* Network related constants */
	static final int SERVER_PORT = 4197; // Port for the sending of pics from the MuonDetector application.
	static final int APP_UDP_PORT = 4197; // Port through which the app send the Poisson spikes trains to the clients. 
    
    /* Math constants */
    static final int SIZE_OF_FLOAT = 4;
    static final int SIZE_OF_BYTE = 1;
    static final int SIZE_OF_INT = 4;
    
    /* Resource constants */
	static final short MAX_DATA_BYTES = 8192;
}
