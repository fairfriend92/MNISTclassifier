<h1>Overmind</h1>

Overmind is a spiking neural network (SNN) simulator for Android devices with a distributed architecture. 

The neural network is organized in sub-networks. Each sub-network is simulated on a separate terminal. Sub-networks exchange spikes over the internet using the User Datagram Protocol (UDP).

Overmind consists of 2 main components: 
  1) A client application, to be run on the Android terminals, that simulates the local sub-network and takes care of communications with other client applications
  2) A server application, that runs on a desktop pc and manages the topology of the virtual network that is made of the client terminals 
  
Communications among terminals are direct, i.e. the terminals form of a P2P network. The server facilitates the "UDP hole punching" technique to make direct communications among clients possible. A brief overview of the simulator can be found <a href="https://drive.google.com/file/d/1HXEZPquvL074W5A8zCYezKZsSuZJRPBV/view?usp=sharing">here</a>; more technical details can be found <a href="https://drive.google.com/file/d/1mpvIt8U_E-32HUVETb6icVovcQidOv6U/view?usp=sharing">here</a>.

<h2>MNISTclassifier</h2>

This is a very simple MNIST classifier that was intended to use as few resources (neurons and synapses) as possible. It is based on the Overmind platform, hence SNNs are used. Rate-based Hebbian learning is also implemented. This application interacts with the Overmind simulator, thus it needs to run in tandem with OvermindServer. Also, at least one terminal needs to run OvermindClient in order to simulate the local sub-network. 
