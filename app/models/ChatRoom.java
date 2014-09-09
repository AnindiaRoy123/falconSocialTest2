package models;

import static akka.pattern.Patterns.ask;
import static java.util.concurrent.TimeUnit.SECONDS;

import helper.UserInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import play.libs.Akka;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.libs.Json;
import play.mvc.WebSocket;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.plugin.RedisPlugin;

/**
 * A chat room is an Actor.
 */
public  class ChatRoom extends UntypedActor {
    
    // Default room.
    static ActorRef defaultRoom = Akka.system().actorOf(Props.create(ChatRoom.class));
    private static final String CHANNEL = "messages";
    private static final String MEMBERS = "members";
    
    static {
    	//add the robot
    	new Robot(defaultRoom);
    	//clearConnPool();
    	
    	//subscribe to the message channel
    	Akka.system().scheduler().scheduleOnce(
    	        Duration.create(10, TimeUnit.MILLISECONDS),
    	        new Runnable() {
    	            public void run() {
    	            	Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
    	            	//Jedis j = getConnection();
    	            	j.subscribe(new MyListener(), CHANNEL);
    	            }
    	        },
    	        Akka.system().dispatcher()
    	);
    }
    
    /**
     * Join the default room.
     */
    public static void join(final String username, WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out) throws Exception{
        System.out.println("joining: " + username);
       // System.out.println("I m on join method ''''''''");
        // Join the default room. Timeout should be longer than the Redis connect timeout (2 seconds)
        String result = (String)Await.result(ask(defaultRoom,new Join(username, out), 3000), Duration.create(3, SECONDS));
        
        if("OK".equals(result)) {
        	//System.out.println("I m on join method and result is====="+result);
            // For each event received on the socket,
            in.onMessage(new Callback<JsonNode>() {
               public void invoke(JsonNode event) {
                   
            	   Talk talk = new Talk(username, event.get("text").asText());
            	   
            	//  Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
            	  Jedis j = getConnection();
            	   try {
            		   //All messages are pushed through the pub/sub channel
            		   j.publish(ChatRoom.CHANNEL, Json.stringify(Json.toJson(talk)));
            	   } finally {
                 	 //play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j);            		   
            		//closeConnection(j);
            	   }
            	   
            	   
               } 
            });
            
            // When the socket is closed.
            in.onClose(new Callback0() {
               public void invoke() {
            	   //System.out.println("I m on join method and about to call quit=====");
                   // Send a Quit message to the room.
                   defaultRoom.tell(new Quit(username), null);
                   
               }
            });
            
        } else {
        	//System.out.println("I m on error else=====");
            
            // Cannot connect, create a Json error.
            ObjectNode error = Json.newObject();
            error.put("error", result);
            
            // Send the error to the socket.
            out.write(error);
            
        }
        
    }
    
    private static void clearConnPool() {
		if(connPool !=null && connPool.size()>0){
			Jedis jedis = (Jedis) connPool.get("Jedis");
			play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(jedis);
			connPool.clear();
		}		
	}

	public static void remoteMessage(Object message) {
    	defaultRoom.tell(message, null);
    }
    
    // Users connected to this node
    Map<String, WebSocket.Out<JsonNode>> members = new HashMap<String, WebSocket.Out<JsonNode>>();
    
    public void onReceive(Object message) throws Exception {
    	 System.out.println("I m on onReceive method ;;;;;");
    	//Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
    	Jedis j = null;
        try {  
        	j = getConnection();
        	if(message instanceof Join) {
        		//System.out.println("I m instanceof Join =====");
        		// Received a Join message
        		Join join = (Join)message;
        		// Check if this username is free.
        		if(j.sismember(MEMBERS, join.username)) {
        			getSender().tell("This username is already used", getSelf());
        		} else {
        			//Add the member to this node and the global roster
        			members.put(join.username, join.channel);
        			j.sadd(MEMBERS, join.username);
        			
        			//Publish the join notification to all nodes
        			RosterNotification rosterNotify = new RosterNotification(join.username, "join");
        			j.publish(ChatRoom.CHANNEL, Json.stringify(Json.toJson(rosterNotify)));
        			getSender().tell("OK", getSelf());
        		}
        		
        	} else if(message instanceof Quit)  {
        		//System.out.println("I m instanceof Quit =====");
        		// Received a Quit message
        		Quit quit = (Quit)message;
        		//Remove the member from this node and the global roster
        		members.remove(quit.username);
        		j.srem(MEMBERS, quit.username);
        		
        		//Publish the quit notification to all nodes
        		RosterNotification rosterNotify = new RosterNotification(quit.username, "quit");
        		//closeConnection();
        		j.publish(ChatRoom.CHANNEL, Json.stringify(Json.toJson(rosterNotify)));
        	} else if(message instanceof RosterNotification) {
        		//System.out.println("I m instanceof RosterNotification =====");
        		//Received a roster notification
        		RosterNotification rosterNotify = (RosterNotification) message;
        		if("join".equals(rosterNotify.direction)) {
        			notifyAll("join", rosterNotify.username, "has entered the room");
        		} else if("quit".equals(rosterNotify.direction)) {
        			notifyAll("quit", rosterNotify.username, "has left the room");
        			
        		}
        	} else if(message instanceof Talk)  {
        		//System.out.println("I m instanceof Talk =====");
        		// Received a Talk message
        		Talk talk = (Talk)message;
        		notifyAll("talk", talk.username, talk.text);
        		
        	} else {
        		unhandled(message);
        	}
        } finally {
        	//play.Play.application().plugin(RedisPlugin.class).sedisPool().
        	// play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j); 
        	//closeConnection(j);
        }  
    }
    
    // Send a Json event to all members connected to this node
    public void notifyAll(String kind, String user, String text) {
        for(WebSocket.Out<JsonNode> channel: members.values()) {
            
            ObjectNode event = Json.newObject();
            event.put("kind", kind);
            event.put("user", user);
            event.put("message", text);
            
            ArrayNode m = event.putArray("members");
            
            //Go to Redis to read the full roster of members. Push it down with the message.
           // Jedis j = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
           Jedis j= getConnection();
            try {
            	for(String u: j.smembers(MEMBERS)) {
            		m.add(u);
            	}
            } finally {
            	//play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(j);            		   
            }
            
            channel.write(event);
        }
    }
    
    
    public static void deleteAllUserRecords() {
		Jedis jedis = null;
		try {
		    //jedis = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
			jedis = getConnection();
		    if (jedis == null) {
		      throw new Exception("Unable to get jedis resource!");
		    }
		    Set<String> keys = jedis.keys(MEMBERS);
		    for (String key : keys) {
		        jedis.del(key);
		    } 
		  } catch (Exception exc) { 

		    throw new RuntimeException("Unable to delete that pattern!");
		  } finally {
		    if (jedis != null) {
		    	//play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(jedis);
		    	//closeConnection(jedis);
		    }
		  }
	}

	public static List<UserInfo> getUserInfo() {
		Jedis jedis = null;
		Set<String> keys = null;
		List<UserInfo> userLst = null;
		try {
		    //jedis = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
			jedis = getConnection();
		    if (jedis == null) {
		      throw new Exception("Unable to get jedis resource!");
		    }
		    userLst = new ArrayList<UserInfo>();
		    for(String str:jedis.keys("MEMBERS:*") ){
		    	userLst.add(new UserInfo(str));
		    }		   
		    //System.out.println("User Infoooooo in the chat room  "+userLst.size());
		     
		  } catch (Exception exc) { 

		    throw new RuntimeException("Unable to get that pattern!");
		  } finally {
		    if (jedis != null) {
		    	//play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(jedis);
		    }
		  }
		  return userLst;
	}
	
	private static Map<String, Object> connPool = new HashMap<String, Object>();
	
	public static Jedis getConnection(){
		Jedis jedis = null;
		System.out.println("i m in getConnection method");
		if(connPool.get("Jedis")!= null){
			jedis = (Jedis) connPool.get("Jedis");
			System.out.println("Jedis not null!!!!!");
		}else{		
			try {
			    jedis = play.Play.application().plugin(RedisPlugin.class).jedisPool().getResource();
		    	System.out.println("Jedis null");
			    connPool.put("Jedis", jedis);
			     
			  } catch (Exception exc) { 
	
			    throw new RuntimeException("Unable to get that pattern!");
			  }
		}
		  return jedis;		
	}
	
	public static void closeConnection(Jedis jedis){
		//Jedis jedis = getConnection();
		if (jedis != null) {
	    	play.Play.application().plugin(RedisPlugin.class).jedisPool().returnResource(jedis);
	    }
  }
		
	
    
    // -- Messages
    
    public static class Join {
        
        final String username;
        final WebSocket.Out<JsonNode> channel;
        
        public String getUsername() {
			return username;
		}
        public String getType() {
        	return "join";
        }

		public Join(String username, WebSocket.Out<JsonNode> channel) {
            this.username = username;
            this.channel = channel;
        }
    }
    
    public static class RosterNotification {
    	
    	final String username;
    	final String direction;
    	
    	public String getUsername() {
    		return username;
    	}
    	public String getDirection() {
    		return direction;
    	}
    	public String getType() {
    		return "rosterNotify";
    	}
    	
    	public RosterNotification(String username, String direction) {
    		this.username = username;
    		this.direction = direction;
    	}
    }
    
    public static class Talk {
        
        final String username;
        final String text;
        
        public String getUsername() {
			return username;
		}
		public String getText() {
			return text;
		}
		public String getType() {
			return "talk";
		}

		public Talk(String username, String text) {
            this.username = username;
            this.text = text;
        }
        
    }
    
    public static class Quit {
        
        final String username;
        
        public String getUsername() {
			return username;
		}
        public String getType() {
        	return "quit";
        }

		public Quit(String username) {
            this.username = username;
        }
        
    }
    
    public static class MyListener extends JedisPubSub {
		@Override
        public void onMessage(String channel, String messageBody) {
			//Process messages from the pub/sub channel
			
	    	JsonNode parsedMessage = Json.parse(messageBody);
	    	Object message = null;
	    	String messageType = parsedMessage.get("type").asText();
	    	//System.out.println("I m on onMessage method ;;;;;"+ channel);
	    	//System.out.println("I m on onMessage method ;;;;;"+ messageBody);
	    	if("talk".equals(messageType)) {	    		
	    		message = new Talk(
	    				parsedMessage.get("username").asText(), 
	    				parsedMessage.get("text").asText()
	    				);
	    	} else if("rosterNotify".equals(messageType)) {	
	    		message = new RosterNotification(
	    				parsedMessage.get("username").asText(),
	    				parsedMessage.get("direction").asText()
	    				);
	    	} else if("quit".equals(messageType)) {	
	    		message = new Quit(
	    				parsedMessage.get("username").asText() 
	    				);	    		
	    	}
			ChatRoom.remoteMessage(message);	        
        }
		@Override
        public void onPMessage(String arg0, String arg1, String arg2) {
        }
		@Override
        public void onPSubscribe(String arg0, int arg1) {
        }
		@Override
        public void onPUnsubscribe(String arg0, int arg1) {
        }
		@Override
        public void onSubscribe(String arg0, int arg1) {
        }
		@Override
        public void onUnsubscribe(String arg0, int arg1) {
        }
    }

	
    
}
