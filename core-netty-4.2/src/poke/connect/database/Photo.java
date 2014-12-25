/**
 * @author vineetbhatkoti
 */
package poke.connect.database;

import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;

import poke.client.util.Constants;

import com.google.protobuf.ByteString;

import eye.Comm.Header;
import eye.Comm.Header.Routing;
import eye.Comm.Payload;
import eye.Comm.Request;

public class Photo {

    /**
     * This function would be used to write a request
     * @param id
     * @param request
     * @return Request
     * @throws UnsupportedEncodingException
     */
	public Request postMsg(UUID id, Request request)
			throws UnsupportedEncodingException {
		Request req = null;
		Request.Builder r = Request.newBuilder();
		Header.Builder r1 = Header.newBuilder();
		Payload.Builder paybuilder = Payload.newBuilder();
		String shard = Constants.SHARD;
		String url = Constants.JDBC_URL;
		Properties props = new Properties();
		props.setProperty(Constants.USER, Constants.USERNAME);
		props.setProperty(Constants.PASSWORD,Constants.MYPASSWD);
		Connection conn;
		try {
			// Retrieve details from the request object
			ByteString photoData = request.getBody().getPhotoPayload()
					.getData();
			String utf8Bytes1 = photoData.toStringUtf8();
			byte[] byteStr = utf8Bytes1.getBytes("UTF8");
			String fileName = request.getBody().getPhotoPayload().getName();
			conn = DriverManager.getConnection(url, props);
			Date date = new Date();
			String key = null;
			Timestamp time = new Timestamp(date.getTime());
			String preparedStatement = "INSERT INTO lifeforce."
					+ shard
					+ " VALUES (?, ?, ?, ?, ?) RETURNING id,name,created,lastmodified";
			System.out.println("The statemnet prepared is: "
					+ preparedStatement);
			PreparedStatement ps = conn.prepareStatement(preparedStatement);
			ps.setString(1, fileName);
			ps.setBytes(2, byteStr);
			ps.setObject(3, id);
			ps.setTimestamp(4, time);
			ps.setTimestamp(5, time);
			ResultSet rs = ps.executeQuery();
			if (rs.next()) {
				// Retrieve the auto generated key(s).
				key = rs.getString(1);
				System.out.println("UUID retrieved --->" + key + "File name "
						+ rs.getString(2) + " Created Timestamp "
						+ rs.getString(3) + "Modified Timstamp  "
						+ rs.getString(4));
			}
			ps.close();
			r1.getPhotoHeaderBuilder()
					.setResponseFlag(eye.Comm.PhotoHeader.ResponseFlag.success)
					.setRequestType(eye.Comm.PhotoHeader.RequestType.write);
			paybuilder.getPhotoPayloadBuilder().setData(photoData)
					.setName(fileName).setUuid(key);
			r.setBody(paybuilder);
			r.setHeader(r1.setRoutingId(Routing.JOBS).setOriginator(2));
			req = r.build();
		} catch (SQLException e) {
			r1.getPhotoHeaderBuilder()
					.setResponseFlag(eye.Comm.PhotoHeader.ResponseFlag.failure)
					.setRequestType(eye.Comm.PhotoHeader.RequestType.write);
			r.setHeader(r1.setRoutingId(Routing.JOBS).setOriginator(2));
			r.setBody(paybuilder);
			req = r.build();
			e.printStackTrace();
		}

		System.out
				.println(" i have saved the data ........its done  !!--------------------------->>>>>>");

		return req;
	}

	/**
	 * This function would be used to read a request for image
	 * @param bucket
	 * @param uuid
	 * @param request
	 * @return Request
	 */
	
	public Request getMsg(int bucket, UUID uuid, Request request) {

		System.out.println(" I am in getMSg  ---- -reading ----------->>");
		Request req = null;
		Request.Builder r = Request.newBuilder();
		Header.Builder r1 = Header.newBuilder();
		Payload.Builder paybuilder = Payload.newBuilder();
		Statement stmt = null;
		String key = null;
		String url = Constants.JDBC_URL;
		Properties props = new Properties();
		props.setProperty(Constants.USER, Constants.USERNAME);
		props.setProperty(Constants.PASSWORD,Constants.MYPASSWD);
		Connection conn;
		try {
			conn = DriverManager.getConnection(url, props);
			stmt = conn.createStatement();
			String preparedStatement = "select id,name,created,lastmodified,photo from lifeforce.collection where id="
					+ "'" + uuid + "'";
			System.out.println("The query formed is :" + preparedStatement);
			ResultSet rs = stmt.executeQuery(preparedStatement);

			if (!rs.next()) {
				//No rows retrieved for the particular UUID
				System.out.println("No data found !!");
				r1.getPhotoHeaderBuilder()
						.setResponseFlag(
								eye.Comm.PhotoHeader.ResponseFlag.failure)
						.setRequestType(eye.Comm.PhotoHeader.RequestType.read);
				r.setBody(paybuilder);
				r.setHeader(r1);
				req = r.build();
			} else {
				//successfully retrieved image for the UUID
				key = rs.getString(1);
				System.out.println("UUID retrieved --->" + key + "File name "
						+ rs.getString(2) + " Created Timestamp "
						+ rs.getString(3) + "Modified Timstamp  "
						+ rs.getString(4) + " Data ---> " + rs.getBytes(5));
				System.out.println(rs.getBytes(5).toString());
				r1.getPhotoHeaderBuilder()
						.setResponseFlag(
								eye.Comm.PhotoHeader.ResponseFlag.success)
						.setRequestType(eye.Comm.PhotoHeader.RequestType.read);
				paybuilder.getPhotoPayloadBuilder()
						.setData(ByteString.copyFrom(rs.getBytes(5)))
						.setName(rs.getString(2)).setUuid(key);
				r.setBody(paybuilder);
				r.setHeader(r1.setRoutingId(Routing.JOBS).setOriginator(2));
				req = r.build();
			}
			conn.close();

		} catch (SQLException e) {
			r1.getPhotoHeaderBuilder()
					.setResponseFlag(eye.Comm.PhotoHeader.ResponseFlag.failure)
					.setRequestType(eye.Comm.PhotoHeader.RequestType.read);
			r.setBody(paybuilder);
			r.setHeader(r1.setRoutingId(Routing.JOBS).setOriginator(2));
			req = r.build();
			e.printStackTrace();
		}

		System.out.println(" i have read with the request !!");
		return req;

	}

	/**
	 * This function would delete an image from the database
	 * @param bucket
	 * @param uuid
	 * @param req
	 * @return Request
	 */
	public Request deleteMsg(int bucket, UUID uuid, Request req) {
		Request req1 = null;
		Request.Builder r = Request.newBuilder();
		Header.Builder r1 = Header.newBuilder();
		Payload.Builder paybuilder = Payload.newBuilder();
		Statement stmt = null;
		String url = Constants.JDBC_URL;
		Properties props = new Properties();
		props.setProperty(Constants.USER, Constants.USERNAME);
		props.setProperty(Constants.PASSWORD,Constants.MYPASSWD);
		Connection conn;
		try {
			conn = DriverManager.getConnection(url, props);
			stmt = conn.createStatement();
			String preparedStatement = "delete from lifeforce.collection where id="
					+ "'" + uuid + "'";
			System.out.println("The query formed is :" + preparedStatement);
			int rowcount = stmt.executeUpdate(preparedStatement);
			if (rowcount == 0) {
				//The rowcount is zero. NO data for corresponding UUID found
				System.out.println("No data found !!");
				r1.getPhotoHeaderBuilder()
						.setResponseFlag(
								eye.Comm.PhotoHeader.ResponseFlag.failure)
						.setRequestType(eye.Comm.PhotoHeader.RequestType.delete);
				r.setBody(paybuilder);
				r.setHeader(r1);
				req1 = r.build();
			} else {
				//Successfully deleted the image for the corresponding UUID
				r1.getPhotoHeaderBuilder()
						.setResponseFlag(
								eye.Comm.PhotoHeader.ResponseFlag.success)
						.setRequestType(eye.Comm.PhotoHeader.RequestType.delete);
				paybuilder.getPhotoPayloadBuilder()
						.setName(req.getBody().getPhotoPayload().getName())
						.setUuid(uuid.toString());
				r.setBody(paybuilder);
				r.setHeader(r1.setRoutingId(Routing.JOBS).setOriginator(2));
				req1 = r.build();
			}
			conn.close();

		} catch (SQLException e) {
			r1.getPhotoHeaderBuilder()
					.setResponseFlag(eye.Comm.PhotoHeader.ResponseFlag.failure)
					.setRequestType(eye.Comm.PhotoHeader.RequestType.delete);
			r.setBody(paybuilder);
			r.setHeader(r1.setRoutingId(Routing.JOBS).setOriginator(2));
			req1 = r.build();
			e.printStackTrace();
		}
		System.out.println(" i have deleted the request !!");
		return req1;
	}

}
