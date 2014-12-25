package poke.demo;

import poke.client.ClientCommand;
import poke.client.ClientPrintListener;
import poke.client.comm.CommConnection;
import poke.client.comm.CommHandler;
import poke.client.comm.CommListener;
import eye.Comm.Request;
import eye.Comm.PhotoResponse.ResponseFlag;

public class Test {

	public static void main(String[] args) throws Exception {
		Request req=null;
//		ClientCommand cli=new ClientCommand("198.168.1.3", 5570);
//		cli.poke("times of india", 3);
		CommListener listener = new ClientPrintListener("hello !!");
//		cli.addListener(listener);
		CommHandler ch=new CommHandler();
		ch.addListener(listener);
		CommConnection cc =new CommConnection("198.168.1.3", 5570);
		cc.sendMessage(req);
	}

}
