/**
 * <pre>
 * 	This program is free software; you can redistribute it and/or modify it under the terms of 
 * the GNU General Public License as published by the Free Software Foundation; either version 3 of the License, 
 * or (at your option) any later version. 
 * 
 * 	This program is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
 * See the GNU General Public License for more details. 
 * 	You should have received a copy of the GNU General Public License along with this program; 
 * if not, write to the Free Software Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * </pre>
 */
package com.meidusa.amoeba.mysql.net;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.collections.map.LRUMap;
import org.apache.log4j.Logger;

import com.meidusa.amoeba.context.ProxyRuntimeContext;
import com.meidusa.amoeba.mysql.filter.IOFilter;
import com.meidusa.amoeba.mysql.filter.PacketIOFilter;
import com.meidusa.amoeba.mysql.handler.PreparedStatmentInfo;
import com.meidusa.amoeba.mysql.jdbc.MysqlDefs;
import com.meidusa.amoeba.mysql.net.packet.FieldPacket;
import com.meidusa.amoeba.mysql.net.packet.MysqlPacketBuffer;
import com.meidusa.amoeba.mysql.net.packet.OkPacket;
import com.meidusa.amoeba.mysql.net.packet.QueryCommandPacket;
import com.meidusa.amoeba.mysql.net.packet.ResultSetHeaderPacket;
import com.meidusa.amoeba.mysql.net.packet.result.MysqlResultSetPacket;
import com.meidusa.amoeba.net.AuthingableConnectionManager;
import com.meidusa.amoeba.net.Connection;
import com.meidusa.amoeba.parser.ParseException;
import com.meidusa.amoeba.util.ThreadLocalMap;

/**
 * 负责连接到 proxy server的客户端连接对象包装
 * 
 * @author <a href=mailto:piratebase@sina.com>Struct chen</a>
 */
public class MysqlClientConnection extends MysqlConnection {
	
	private static Logger logger = Logger
			.getLogger(MysqlClientConnection.class);
	private static Logger authLogger = Logger.getLogger("auth");
	private static Logger lastInsertID = Logger.getLogger("lastInsertId");
	static List<IOFilter> filterList = new ArrayList<IOFilter>();
	static {
		filterList.add(new PacketIOFilter());
	}
	
	private Lock lock = new ReentrantLock(true);
	private long createTime = System.currentTimeMillis();
	public void afterAuth(){
		if(authLogger.isDebugEnabled()){
			authLogger.debug("authentication time:"+(System.currentTimeMillis()-createTime) +"   Id="+this.getInetAddress());
		}
	}
	// 保存服务端发送的随机用于客户端加密的字符串
	protected String seed;
	
	private long lastInsertId;
	
	// 保存客户端返回的加密过的字符串
	protected byte[] authenticationMessage;
	public MysqlResultSetPacket lastPacketResult = new MysqlResultSetPacket(null);
	{
		lastPacketResult.resulthead = new ResultSetHeaderPacket();
		lastPacketResult.resulthead.columns = 1;
		lastPacketResult.fieldPackets = new FieldPacket[1];
		FieldPacket field = new FieldPacket();
		field.type = MysqlDefs.FIELD_TYPE_LONGLONG;
		field.name = "last_insert_id";
		field.catalog = "def";
		field.length = 20;
		lastPacketResult.fieldPackets[0] = field; 
		
	}
	private List<byte[]> longDataList = new ArrayList<byte[]>();

	private List<byte[]> unmodifiableLongDataList = Collections
			.unmodifiableList(longDataList);

	/** 存储sql,statmentId对 */
	private final Map<String, Long> sql_statment_id_map = Collections
			.synchronizedMap(new HashMap<String, Long>(256));
	private AtomicLong atomicLong = new AtomicLong(1);

	/**
	 * 采用LRU缓存这些preparedStatment信息 key=statmentId value=PreparedStatmentInfo
	 * object
	 */
	@SuppressWarnings("unchecked")
	private final Map<Long, PreparedStatmentInfo> prepared_statment_map = Collections
			.synchronizedMap(new LRUMap(256) {

				private static final long serialVersionUID = 1L;

				protected boolean removeLRU(LinkEntry entry) {
					PreparedStatmentInfo info = (PreparedStatmentInfo) entry
							.getValue();
					sql_statment_id_map.remove(info.getPreparedStatment());
					return true;
				}

				public PreparedStatmentInfo remove(Object key) {
					PreparedStatmentInfo info = (PreparedStatmentInfo) super
							.remove(key);
					sql_statment_id_map.remove(info.getPreparedStatment());
					return info;
				}

				public Object put(Object key, Object value) {
					PreparedStatmentInfo info = (PreparedStatmentInfo) value;
					sql_statment_id_map.put(info.getPreparedStatment(),
							(Long) key);
					return super.put(key, value);
				}

				public void putAll(Map map) {
					for (Iterator it = map.entrySet().iterator(); it.hasNext();) {
						Map.Entry<Long, PreparedStatmentInfo> entry = (Map.Entry<Long, PreparedStatmentInfo>) it
								.next();
						sql_statment_id_map.put(entry.getValue()
								.getPreparedStatment(), entry.getKey());
					}
					super.putAll(map);
				}
			});

	public MysqlClientConnection(SocketChannel channel, long createStamp) {
		super(channel, createStamp);
	}

	public PreparedStatmentInfo getPreparedStatmentInfo(long id) {
		return prepared_statment_map.get(id);
	}

	public PreparedStatmentInfo getPreparedStatmentInfo(String preparedSql) throws ParseException{
		Long id = sql_statment_id_map.get(preparedSql);
		PreparedStatmentInfo info = null;
		if (id == null) {
			info = new PreparedStatmentInfo(this, atomicLong.getAndIncrement(),
					preparedSql);
			prepared_statment_map.put(info.getStatmentId(), info);
		} else {
			info = getPreparedStatmentInfo(id);
		}
		return info;
	}

	public String getSeed() {
		return seed;
	}

	public void setSeed(String seed) {
		this.seed = seed;
	}

	public byte[] getAuthenticationMessage() {
		return authenticationMessage;
	}

	public void handleMessage(Connection conn) {
		
		byte[] message = this.getInQueue().getNonBlocking();
		// 在未验证通过的时候
		/** 此时接收到的应该是认证数据，保存数据为认证提供数据 */
		this.authenticationMessage = message;
		((AuthingableConnectionManager) _cmgr).getAuthenticator()
				.authenticateConnection(this);
	}

    protected void doReceiveMessage(byte[] message){
    	if(MysqlPacketBuffer.isPacketType(message, QueryCommandPacket.COM_QUIT)){
    		postClose(null);
    		return;
		}else if(MysqlPacketBuffer.isPacketType(message, QueryCommandPacket.COM_STMT_CLOSE)){
			//
			return;
		}else if(MysqlPacketBuffer.isPacketType(message, QueryCommandPacket.COM_PING)){
			OkPacket ok = new OkPacket();
			ok.affectedRows = 0;
			ok.insertId = 0;
			ok.packetId = 1;
			ok.serverStatus = 2;
			this.postMessage(ok.toByteBuffer(null).array());
			return;
		} else if (MysqlPacketBuffer.isPacketType(message, QueryCommandPacket.COM_STMT_SEND_LONG_DATA)) {
            this.addLongData(message);
            return;
        }
    	super.doReceiveMessage(message);
    }
    
	protected void messageProcess() {
		
		Executor executor = null;
		if(isAuthenticatedSeted()){
			executor = ProxyRuntimeContext.getInstance().getClientSideExecutor();
		}else{
			executor = ProxyRuntimeContext.getInstance().getServerSideExecutor();
		}
		
		executor.execute(new Runnable() {
			public void run() {
				synchronized(MysqlClientConnection.this.getMessageHandler()){
					try {
						MysqlClientConnection.this.getMessageHandler().handleMessage(MysqlClientConnection.this);
					} finally {
						ThreadLocalMap.reset();
					}
				}
			}
		});
	}

	public void addLongData(byte[] longData) {
		longDataList.add(longData);
	}

	public void clearLongData() {
		longDataList.clear();
	}

	public List<byte[]> getLongDataList() {
		return unmodifiableLongDataList;
	}
	
	public long getLastInsertId() {
		if(lastInsertID.isDebugEnabled()){
			lastInsertID.debug("get last_insert_Id="+lastInsertId);
		}
		return lastInsertId;
	}

	public void setLastInsertId(long lastInsertId) {
		if(lastInsertID.isDebugEnabled()){
			lastInsertID.debug("set last_insert_Id="+lastInsertId);
		}
		this.lastInsertId = lastInsertId;
	}

	/**
	 * 正在处于验证的Connection Idle时间可以设置相应的少一点。
	 */
	public boolean checkIdle(long now) {
		if (isAuthenticated()) {
			return false;
		} else {
			long idleMillis = now - _lastEvent;
			if (idleMillis < 5000) {
				return false;
			}
			if (isClosed()) {
				return true;
			}
			return true;
		}
	}
}
