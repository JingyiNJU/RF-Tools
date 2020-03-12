package com.example.sdksample;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * ---------------------------------------------------------------------------------------
 *  TestTools.java程序仅对每个功能函数提供默认的测试样例，可通过testflag变量选择需要调用的函数，其中：
 *  testflag=1~5主要为《射频识别技术——原理、协议及系统设计》一书中的实验部分(第8章)提供支持； 				
 *  testflag=6~13是常用的采集数据的函数，可根据需求选择调用； 						 				
 *  具体函数功能及详细使用说明请参见doc文档 RF_Tools/doc/index.html。 	
 *  Text file encoding:	UTF-8										
 * ---------------------------------------------------------------------------------------
 */
public class TestTools {

	public static void main(String[] args) {
		
		// reader IP
		String hostname = "192.168.100.169";
		//String hostname = "192.168.1.100";
		// 掩码
		String targetEpc;
		
		// 天线端口选择  R220:{1,2}  R420:{1,2,3,4}
		short[] port = new short[] {1};//仅使用RF-Ware自带的1号天线
		
		// 传输功率设定
		double TxPowerinDbm = 25;// Dbm 10~32.5
		
		// 接收灵敏度设定
		double RxSensitivityinDbm = -70.0;
		
		// 模式选择
		// SpeedwayR220: AutoSetDenseReader/DenseReaderM4/DenseReaderM4Two/DenseReaderM8
		// SpeedwayR420: AutoSetDenseReader/AutoSetDenseReaderDeepScan/AutoSetStaticDRM/AutoSetStaticFast/MaxThroughput/Hybrid/DenseReaderM4/DenseReaderM4Two/DenseReaderM8
		String mode = "MaxThroughput";
		
		// 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率 （testflag=6~13中使用）
		int RSSI = 1, phase = 1, time = 1, portN = 1, freq = 1;
		
		// label记录每个EPC对应的tag编号 <String:EPC,Integer:tagID> （testflag=6~13中使用）
		HashMap<String, Integer> label = new HashMap<String, Integer>();

		// 选择测试函数
		int testflag = 6;

		switch (testflag) {
		case 1:
			// test readEPC
			// 请将EPC的末四位按照tagID编号，如0001，0002，... ，0010，0011，...
			targetEpc = "2018";
			int num1 = 7;// 标签基数
			ArrayList<String[]> EPCArray = RFRead.readEPC(hostname, targetEpc, num1, "EPC");
			
			// test getInfo
			double[] info = new double[4];
			int N = 3;// 连续N轮扫描统计的标签总个数不变，则认为静态识别过程结束
			info = RFRead.getInfo(EPCArray, num1, N, "Info");
			System.out.println("标签基数:" + num1 + ", 读取轮数:" + (int) info[0] + ", 扫描时延:" + String.format("%.2f", info[1])
					+ "s, 实际统计个数:" + (int) info[2] + ", 漏读率:" + String.format("%.1f", info[3] * 100) + "%");
			break;

		case 2:
			// test singleRead
			targetEpc = "201803190000000000000001";//读取单个标签，请将掩码直接设置为对应的EPC
			double rssi = RFRead.singleRead(hostname, targetEpc, 'R');
			System.out.println("采集点的RSSI:" + rssi + "\n");
			double Phase = RFRead.singleRead(hostname, targetEpc, 'P');
			System.out.println("采集点的phase:" + Phase + "\n");
			break;

		case 3:
			// test useSingleRead
			targetEpc = "201803190000000000000001";
			RFRead.useSingleRead(hostname, targetEpc, "useSingleRead");
			break;

		case 4:
			// test read2RSSI
			/*
			 * 特别说明 在使用 read2RSSI 和 LANDMARC 时，请确保标签EPC编号的末四位从0开始顺次编号，例：
			 * 201909110000000000000000 201909110000000000000001 201909110000000000000002
			 * 201909110000000000000003 …… 20190911000000000000000N
			 * 输入参数中的标签数num4和num5为N+1,即参考标签数（N）+待定位标签数（1）
			 * 其中201909110000000000000000为LANDMARC中待定位标签，其余为参考标签
			 * 
			 */
			// 根据教材中的设定,num4取21
			int num4 = 7;
			targetEpc = "20190911000000000000";
			double[][] RSSI2 = RFRead.read2RSSI(hostname, targetEpc, num4, "read2RSSI");
			break;

		case 5:
			// test LANDMARC
			// 需确保两天线都能读到所有标签的RSSI
			// 掩码设置
			targetEpc = "20190911000000000000";
			// 根据教材中的设定,num5取21
			int num5 = 7;
			double[][] referLoc = new double[num5][2];
			/*
			 * 需输入确定所有参考标签的x,y坐标信息 referLoc[i][0]为x坐标，referLoc[i][1]为y坐标
			 */
			referLoc[1][0] = 0;
			referLoc[1][1] = 0;
			referLoc[2][0] = 0;
			referLoc[2][1] = 1;
			referLoc[3][0] = 1;
			referLoc[3][1] = 0;
			referLoc[4][0] = 1;
			referLoc[4][1] = 1;
			referLoc[5][0] = 2;
			referLoc[5][1] = 0;
			referLoc[6][0] = 2;
			referLoc[6][1] = 1;
			// referLoc[7][0] = 1.1;referLoc[][1] = 4.1;
			// …… ……
			//// referLoc[20][0] = 1.1;referLoc[20][1] = 4.1;
			double[] xy = RFRead.LANDMARC(referLoc, hostname, targetEpc, num5, "LANDMARC");
			System.out.println("目标标签的位置为 X:" + String.format("%.2f", xy[0]) + ",Y:" + String.format("%.2f", xy[1]));
			break;

		case 6:
			// test readAll
			System.out.println("————调用函数readAll—————");
			// 掩码设置
			targetEpc = "2019";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0
			// EPC每4位之间需用空格分开
			label.put("2019 0000 0000 0000 0000 0001", 1);
			label.put("2019 0000 0000 0000 0000 0002", 2);
			label.put("2019 0000 0000 0000 0000 0003", 3);
			label.put("2018 0320 0000 0000 0000 0001", 4);
			label.put("2018 0320 0000 0000 0000 00AE", 5);
			// 是否在控制台输出读到的信息
			boolean output6 = true;
			// 存储的文件名
			String filename6 = "readAll";
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1; // 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			// 调用readAll采集数据
			RFRead.readAll(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc, filename6, output6, RSSI,
					phase, time, portN, freq);
			break;

		case 7:
			// test readAllF
			System.out.println("————调用函数readAllF—————");
			// 掩码设置
			targetEpc = "20180319";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0
			// EPC每4位之间需用空格分开
			label.put("2018 0319 0000 0000 0000 0001", 1);
			label.put("2018 0319 0000 0000 0000 0002", 2);
			label.put("2018 0319 0000 0000 0000 0003", 3);
			label.put("2018 0320 0000 0000 0000 0001", 4);
			// 是否在控制台输出读到的信息
			boolean output7 = true;
			// 存储的文件名
			String filename7 = "readAllF";
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1; // 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			// 调用readAllF采集数据
			RFRead.readAllF(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc, filename7, output7,
					RSSI, phase, time, portN, freq);
			break;

		case 8:
			// test readAllT
			System.out.println("————调用函数readAllT—————");
			// 掩码设置
			targetEpc = "2018";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0,EPC每4位之间需用空格分开
//			label.put("2017 0000 0000 0000 0000 0001", 1);
//			label.put("2017 0000 0000 0000 0000 0002", 2);
			label.put("2018 0319 0000 0000 0000 0002", 2);
			label.put("2018 0319 0000 0000 0000 0001", 1);
			label.put("2018 0319 0000 0000 0000 0003", 3);
	//		label.put("2019 0529 0000 0000 0000 0001", 1);
//			label.put("2019 0000 0000 0000 0000 0004", 4);
//			label.put("2019 0000 0000 0000 0000 0005", 5);
//			label.put("2018 0319 0000 0000 0000 0003", 3);
//			label.put("2018 0320 0000 0000 0000 0001", 4);
//			label.put("2018 0320 0000 0000 0000 00AE", 5);
			// 是否在控制台输出读到的信息
			boolean output8 = true;
			// 存储的文件名
			String filename8 = "readAllT";
			// 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1;
			// 控制采集时常为5s
			int howlong8 = 5000;
			// 调用readAllT采集数据
			RFRead.readAllT(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc, filename8, output8,
					howlong8, RSSI, phase, time, portN, freq);
			break;

		case 9:
			// test readAllTF
			System.out.println("————调用函数readAllTF—————");
			// 掩码设置
			targetEpc = "201900000000000000000001";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0,EPC每4位之间需用空格分开
			label.put("2019 0000 0000 0000 0000 0001", 1);
//			label.put("2018 0319 0000 0000 0000 0001", 1);
//			label.put("2018 0319 0000 0000 0000 0002", 2);
//			label.put("2018 0319 0000 0000 0000 0003", 3);
//			label.put("2018 0320 0000 0000 0000 0001", 4);
//			label.put("2018 0320 0000 0000 0000 00AE", 5);
			// 是否在控制台输出读到的信息
			boolean output9 = true;
			// 存储的文件名
			String filename9 = "readAllTF";
			// 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1;
			// 控制采集时常为2s
			int howlong9 = 2000;
			// 调用readAllTF采集数据
			RFRead.readAllTF(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc, filename9, output9,
					howlong9, RSSI, phase, time, portN, freq);
			break;

		case 10:
			// test readAllF
			System.out.println("————调用函数readAllTF—————");
			// 掩码设置
			targetEpc = "201803";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0,EPC每4位之间需用空格分开
			label.put("2018 0319 0000 0000 0000 0001", 1);
			label.put("2018 0319 0000 0000 0000 0002", 2);
			label.put("2018 0319 0000 0000 0000 0003", 3);
			label.put("2018 0320 0000 0000 0000 0001", 4);
			label.put("2018 0320 0000 0000 0000 00AE", 5);
			// 是否在控制台输出读到的信息
			boolean output10 = true;
			// 存储的文件名
			String filename10 = "readAll";
			// 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1;
			// 调用readAll采集数据
			// 统计阅读率
			int readRate = 1;
			int[] readRate10 = RFRead.readAll(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc,
					filename10, output10, RSSI, phase, time, portN, freq, readRate);

			System.out.println("标签阅读率依次为：");
			for (int i = 0; i < readRate10.length; i++) {
				if (readRate10[i] != 0) {
					System.out.println("标签" + i + ":" + readRate10[i]);
				}
			}
			break;

		case 11:
			// test test readAllF
			System.out.println("————调用函数readAllF—————");
			// 掩码设置
			targetEpc = "20180319";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0
			// EPC每4位之间需用空格分开
			label.put("2018 0319 0000 0000 0000 0001", 1);
			label.put("2018 0319 0000 0000 0000 0002", 2);
			label.put("2018 0319 0000 0000 0000 0003", 3);
			label.put("2018 0320 0000 0000 0000 0001", 4);
			// 是否在控制台输出读到的信息
			boolean output11 = true;
			// 存储的文件名
			String filename11 = "readAllF";
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1; // 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			// 调用readAllF采集数据(统计阅读率）
			int[] readRate11 = RFRead.readAllF(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc,
					filename11, output11, RSSI, phase, time, portN, freq, 1);
			System.out.println("标签阅读率依次为：");
			for (int i = 0; i < readRate11.length; i++) {
				if (readRate11[i] != 0) {
					System.out.println("标签" + i + ":" + readRate11[i]);
				}
			}
			break;

		case 12:
			// test readAllT
			System.out.println("————调用函数readAllT—————");
			// 掩码设置
			targetEpc = "201900000000000000000001";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0,EPC每4位之间需用空格分开
			label.put("2019 0000 0000 0000 0000 0001", 1);
//			label.put("2018 0319 0000 0000 0000 0001", 1);
//			label.put("2018 0319 0000 0000 0000 0002", 2);
//			label.put("2018 0319 0000 0000 0000 0003", 3);
//			label.put("2018 0320 0000 0000 0000 0001", 4);
//			label.put("2018 0320 0000 0000 0000 00AE", 5);
			// 是否在控制台输出读到的信息
			boolean output12 = true;
			// 存储的文件名
			String filename12 = "readAllT";
			// 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1;
			// 控制采集时常为2s
			int howlong12 = 2000;
			// 调用readAllT采集数据
			int[] readRate12 = RFRead.readAllT(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc,
					filename12, output12, howlong12, RSSI, phase, time, portN, freq, 1);
			System.out.println("标签阅读率依次为：");
			for (int i = 0; i < readRate12.length; i++) {
				if (readRate12[i] != 0) {
					System.out.println("标签" + i + ":" + readRate12[i]);
				}
			}
			break;

		case 13:
			// test readAllTF
			System.out.println("————调用函数readAllTF—————");
			// 掩码设置
			targetEpc = "201803";// 掩码每4位之间无需空格
			// 记录EPC对应的tag编号，未标记编号的tag在输出文件中将默认为编号0,EPC每4位之间需用空格分开
			label.put("2018 0319 0000 0000 0000 0001", 1);
			label.put("2018 0319 0000 0000 0000 0002", 2);
			label.put("2018 0319 0000 0000 0000 0003", 3);
			label.put("2018 0320 0000 0000 0000 0001", 4);
			label.put("2018 0320 0000 0000 0000 00AE", 5);
			// 是否在控制台输出读到的信息
			boolean output13 = true;
			// 存储的文件名
			String filename13 = "readAllTF";
			// 用0或1表示是否需要采集RSSI/phase/时间/天线号/频率
			RSSI = 1;
			phase = 1;
			time = 1;
			portN = 1;
			freq = 1;
			// 控制采集时常为2s
			int howlong13 = 2000;
			// 调用readAllT采集数据
			int[] readRate13 = RFRead.readAllTF(hostname, port, TxPowerinDbm, RxSensitivityinDbm, mode, label, targetEpc,
					filename13, output13, howlong13, RSSI, phase, time, portN, freq, 1);
			System.out.println("标签阅读率依次为：");
			for (int i = 0; i < readRate13.length; i++) {
				if (readRate13[i] != 0) {
					System.out.println("标签" + i + ":" + readRate13[i]);
				}
			}
			break;

		}

	}

}
