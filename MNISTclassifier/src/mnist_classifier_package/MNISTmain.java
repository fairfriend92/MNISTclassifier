package mnist_classifier_package;
import java.io.File;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;

import overmind_utilities.AppInfo;
import overmind_utilities.ServerInterfacer;

public class MNISTmain {
	private static int SUCCESS = 1, ERROR = 0, 
			IMG_FILE_MN = 2051, // Image file magic number
			LBL_FILE_MN = 2049; // Label file magic number
	
	static byte[][] trainingSetImages, testSetImages;
	static byte[] trainingSetLabels, testSetLabels;
	
	public static void main(String[] args) {
		AppInfo.name = "MNISTclassifier";
		ServerInterfacer serverInterfacer = new ServerInterfacer(args);
		serverInterfacer.start();
		
		if (loadDatasets() == ERROR) {
			System.out.println("Error! Problem occurred while loading a dataset");
			return;
		}					
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
	
	private static byte[][] readImageFile(File imageFile) {
		byte[][] result = null;
		
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
			byte[][] images = new byte[numOfImages][imageResolution];
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
	
	private static byte[] readLabelFile(File labelFile) {
		byte[] result = null;
		
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
			byte[] labels = new byte[numOfItems];
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