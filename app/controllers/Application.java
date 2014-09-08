package controllers;

import static play.libs.Json.toJson;
import helper.UserInfo;

import java.util.List;
import java.util.Set;

import models.ChatRoom;
import play.api.Play;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.WebSocket;
import scala.App;
import views.html.chatRoom;
import views.html.index;

import com.fasterxml.jackson.databind.JsonNode;

public class Application extends Controller {
  
    /**
     * Display the home page.
     */
	private static Boolean status= false;
    public static Result index() {
    	if(!status){
    		//
    		Integer val = Integer.parseInt(play.Play.application().configuration().getString("deleteStat"));
    		if(val==1){
    			ChatRoom.deleteAllUserRecords();
    			System.out.println("I m here for inside fst time!!!!");
    		}
    		System.out.println("I m here for fst time!!!!"+val);
    		status = true;
    	
    	}
        return ok(index.render());
    }
  
    /**
     * Display the chat room.
     */
    public static Result chatRoom(String username) {
        if(username == null || username.trim().equals("")) {
            flash("error", "Please choose a valid username.");
            return redirect(routes.Application.index());
        }
        
        return ok(chatRoom.render(username));
    }

    public static Result chatRoomJs(String username) {
        return ok(views.js.chatRoom.render(username));
    }
    
    /**
     * Handle the chat websocket.
     */
    public static WebSocket<JsonNode> chat(final String username) {
        return new WebSocket<JsonNode>() {
            
            // Called when the Websocket Handshake is done.
            public void onReady(WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out){
                
                // Join the chat room.
                try { 
                    ChatRoom.join(username, in, out);
                	//ChatRoom.getInstance().join(username, in, out);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };
    }
    
    /**
     * get all user info.
     */
    
    public static Result getUserInfo(){
    	Set<String> userInfo = null;
    	try{
	    	userInfo = ChatRoom.getUserInfo();		
	    	return ok(toJson(userInfo));
    	}catch(Exception ex){
    		ex.printStackTrace();
    	}
    	return ok(toJson(userInfo));
    }
  
}
