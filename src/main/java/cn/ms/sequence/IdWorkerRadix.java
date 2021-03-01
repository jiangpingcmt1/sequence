package cn.ms.sequence;

import cn.ms.sequence.IdWorker;

/**
 * 各进制全局唯一数
* @author hulk
* @E-mail:29572320@qq.com
* @version Create on:  2017年5月5日 下午4:23:55
* Class description
*/



public class IdWorkerRadix {



	public static String getIdStr(){
		return IdWorker.getIdStr();
	}

	public static String getBinaryId(){
		return Long.toBinaryString(IdWorker.getId());
	}
	
	public static String getOctId(){
		return Long.toOctalString(IdWorker.getId());
	}
	
	public static String getHexId(){
		return Long.toHexString(IdWorker.getId());
	}

	public static String getHEXId(){
		return Long.toHexString(IdWorker.getId()).toUpperCase();
	}
	
	public static String getDtmId(){
		return Long.toString(IdWorker.getId(), 32);
	}

	public static String getDTMId(){
		return Long.toString(IdWorker.getId(), 32).toUpperCase();
	}

	public static String getRadixId(int radix){
		return Long.toString(IdWorker.getId(), radix);
	}

	public static String getRADIXId(int radix){
		return Long.toString(IdWorker.getId(), radix).toUpperCase();
	}
	


}
