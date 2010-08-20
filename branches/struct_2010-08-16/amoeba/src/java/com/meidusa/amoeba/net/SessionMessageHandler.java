/*
 * 	This program is free software; you can redistribute it and/or modify it under the terms of 
 * the GNU General Public License as published by the Free Software Foundation; either version 3 of the License, 
 * or (at your option) any later version. 
 * 
 * 	This program is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
 * See the GNU General Public License for more details. 
 * 	You should have received a copy of the GNU General Public License along with this program; 
 * if not, write to the Free Software Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package com.meidusa.amoeba.net;


/**
 * 
 * 网络数据处理接口
 * 
 * @author <a href=mailto:piratebase@sina.com>Struct chen</a>
 * 
 */
public interface SessionMessageHandler {
	
	/**
	 * 
	 * 处理一个完整的数据包消息
	 * 
	 * @param conn 表示该数据是从当前conn 发送过来的
	 * 
	 * @param message 当前handle 的消息数据
	 */
	public void handleMessage(Connection conn,byte[] message);
	
}
