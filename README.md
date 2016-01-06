# Forwarder #

## Overview ##

Forwarder is an application that will convert SWG's UDP protocol into a basic TCP protocol.

### Benefits ###
* Reduced memory usage on server
* Less networking code
* Less bandwidth
* Faster connection detection (both connecting and disconnecting)
* Reliable connection persistence
* and many more

-----------------------

# How it works #
The way it works is by creating a very basic UDP server on each client, and having SWG connect to that instead of Holocore. Then the Forwarder connects via TCP to Holocore and forwards all SWG packets that were received over UDP, to TCP.

## Setup ##

### Single Instance ###
1. Start up the forwarder
2. Open up login.cfg and set "loginServerAddress" to "127.0.0.1" and "loginServerPort" to whatever is listed next to the server connection status (e.x. 44453)
3. Start up the SWG client

### Multiple Instances ###
Repeat above for all SWG clients. It's important that you modify login.cfg and start the client before modifying login.cfg again

-----------------------

# Example login.cfg #

```
#!properties

[ClientGame]
	loginServerAddress=127.0.0.1
	loginServerPort=44453
	loginClientID=
	autoConnectToLoginServer=false
	skipIntro=1
	skipSplash=1
	0fd345d9 = true
	logReportFatals = true
	logStderr = true
[Station]
	subscriptionFeatures=1
	gameFeatures=65535
```