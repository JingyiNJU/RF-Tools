package com.example.sdksample;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.impinj.octane.AntennaConfigGroup;
import com.impinj.octane.BitPointers;
import com.impinj.octane.FeatureSet;
import com.impinj.octane.ImpinjReader;
import com.impinj.octane.MemoryBank;
import com.impinj.octane.OctaneSdkException;
import com.impinj.octane.ReaderMode;
import com.impinj.octane.ReaderModel;
import com.impinj.octane.ReportConfig;
import com.impinj.octane.ReportMode;
import com.impinj.octane.Settings;
import com.impinj.octane.Tag;
import com.impinj.octane.TagFilter;
import com.impinj.octane.TagFilterMode;
import com.impinj.octane.TagFilterOp;
import com.impinj.octane.TagReport;

public class RFRead {
	 
	/**
	 * 对N个标签进行持续扫描，获取连续的EPC号和对应的时间并写文件(.csv)
	 * 返回的EPC是每个查询轮回中在单一时隙扫描到的EPC序列，并将多个查询轮回中扫描到的标签EPC序列串行输出
	 * 文件写入的内容为：
	 * EPC,time
	 * EPC,time
	 * EPC,time
	 * ...,...
	 * @param hostname 阅读器的ip地址
	 * @param targetEpc 标签掩码(80bits)
	 * @param num 要识别的标签总个数（默认所有标签EPC的末尾四位按0001、0002、0003...9999编号）
	 * @param filename 要写入的文件名
	 * @return 所有的EPC号和对应的时间	
	 * 
	 */
	public static ArrayList<String[]> readEPC(String hostname,String targetmask,int num, String filename) {

		// 调用示例：
		// ArrayList<String[]> list1 = RFTools.readEPC("192.168.100.169","20190706000000000000",20,"identification1");* 		
		/** 存储EPC号和对应的时间 */
		ArrayList<String[]> EPCs = new ArrayList<String[]>();
		/** 存储要写文件的内容*/
		ArrayList<String> write = new ArrayList<String>();
		
		/** 根据num设定扫描时长 */
		int duration;
		if (num <= 60)
			duration = 1000;
		else if (num <= 100)
			duration = 2000;
		else
			duration = 5000;
		// 读取EPC time
		try {

			ImpinjReader reader = new ImpinjReader();

			System.out.println("Connecting");
			reader.connect(hostname);

			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回

			settings.setReaderMode(ReaderMode.AutoSetDenseReader);

			//设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length()*4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);// 80位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st "+targetmask.length()*4+" bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(new short[] { 1 });
			antennas.getAntenna((short) 1).setIsMaxRxSensitivity(false);
			antennas.getAntenna((short) 1).setIsMaxTxPower(false);
			antennas.getAntenna((short) 1).setTxPowerinDbm(25.0);
			antennas.getAntenna((short) 1).setRxSensitivityinDbm(-70);

			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {
						// 时间的处理
						String[] temp = new String[2];
						temp[0] = t.getEpc().toString();
						temp[1] = t.getFirstSeenTime().ToString();
						EPCs.add(temp);
						write.add(temp[0]+","+temp[1]);
						//System.out.println("EPC:" + temp[0] + "  time:" + temp[1]);// 输出所有的EPC和对应的时间
						System.out.println("EPC:" + temp[0] + "  time:" + temp[1] +"phase:"+Double.toString(t.getPhaseAngleInRadians()));
					}
				}
			});

			System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("Starting");
			reader.start();

			Thread.sleep(duration);

			reader.stop();
			reader.disconnect();
		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}
		//写文件
		RFRead.writefileM(filename,write);
		System.out.println("请于当前目录下查看保存的"+filename+".csv数据文件");
		return EPCs;

	}

	/**
	 * 根据扫描获得的EPC(由readEPC()获得)计算读取轮数、识别时间、识别个数、漏读率.
	 * 标签个数上限为200
	 * 文件写入的内容为：
	 * 读取轮数，识别时间，识别个数，漏读率 
	 * @param list readEPC函数返回的list，包含EPC,时间
	 * @param num 总的标签数
	 * @param N 设定的N值(连续N轮扫描统计的标签总个数不变，则认为静态识别过程结束)
	 * @param filename 要写入的文件名
 	 * @return 读取轮数、识别时间、识别个数、漏读率 
	 */
	public static double[] getInfo(ArrayList<String[]> list, int num, int N,String filename) {
		/** 存储要写文件的内容*/
		ArrayList<String> write = new ArrayList<String>();
		
		int[] hashArray = new int[201];
		Arrays.fill(hashArray, 0);
		double[] info = new double[4];// 存储计算的信息
		int total = 0;// 轮数
		int tempN = 1;
		int Numb = 0;
		int NumbNew = 0;
		long starttime = 0;
		long endtime = 0;

		if(list.size()==0) {
			System.out.println("未读到标签，请检查标签和实验设置是否正确！");
			System.exit(0);
		}
		// 取list中第一个value记作开始时间
		starttime = Long.parseLong(list.get(0)[1]);
		for (int i = 0; i < list.size(); i++) {
			// 每取到一个EPC就把hash列表里对应的位置+1
			String subEPCStr = list.get(i)[0].substring(25, 29);// 从25开始取到不包括29的地方
			// 取EPC后4位
			int subEPCInt = (int) Integer.parseInt(subEPCStr);
			// 之前没有出现过该EPC
			if (hashArray[subEPCInt] == 0) {
				hashArray[subEPCInt]++;
			}
			// 出现重复的EPC表示上一轮结束
			else {
				write.add("over");
				total++;// 轮数++
				// 统计读到的标签数
				for (int a : hashArray) {
					if (a != 0) {
						NumbNew++;// 当前轮读取的标签数
					}
				}
				// 若标签数与上一轮标签数相等
				if (NumbNew == Numb)
					tempN++;
				else
					Numb = NumbNew;
				NumbNew = 0;
				//输入的num错误
				if(Numb>num){
					System.out.println("getInfo:@param num 输入的标签数num有误！");
					System.exit(0);
				}
				if (tempN >= N) {
					//write.add("round number,recognition time/s,识别标签数,漏读率");
					info[0] = total;// 读取轮数
					endtime = Long.parseLong(list.get(i)[1]);
					info[1] = (endtime - starttime) * 1.0 / 1000000;// 秒
					info[2] = Numb;// 识别标签数
					info[3] = 1 - Numb * 1.0 / num;// 漏读率
					write.add(info[0]+","+info[1]+","+info[2]+","+info[3]);
					break;
				} else {
					Arrays.fill(hashArray, 0);
					hashArray[subEPCInt]++;
				}
			}
			write.add(list.get(i)[0]+","+list.get(i)[1]);
		}
		//写文件
		RFRead.writefileM(filename, write);
		// System.out.println(info[0]+" "+info[1]+" "+info[2]+" "+info[3]);
		System.out.println("请于当前目录下查看保存的"+filename+".csv数据文件");
		return info;
	}
	
	/**
	 * 采集某一个位置的RSSI值或相位值
	 * 数据采集以敲击回车结束，取采集时间段内的平均值作为结果
	 * @param hostname 阅读器ip
	 * @param targetEpc 标签EPC
	 * @param RorP 1表示采集RSSI 2表示采集Phase
	 * @return 采集点的信号值
	 */
	public static double singleRead(String hostname,String targetEpc,char RorP) {

		Scanner sc = new Scanner(System.in);
		/** 设定目标标签mask */
		//String targetEpc = "201907060000000000000001";// 按照日期设定

		/** 存储RSSI或相位值 */
		ArrayList<Double> signal = new ArrayList<Double>();

		try {

			//String hostname = "192.168.100.169";// 填写阅读器的IP
			ImpinjReader reader = new ImpinjReader();

			//System.out.println("Connecting");
			reader.connect(hostname);

			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回

			settings.setReaderMode(ReaderMode.AutoSetDenseReader);

			// 设置标签读取过滤器
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(96);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetEpc);// 96位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			//System.out.println("Matching 1st 96 bits of epc " + targetEpc);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(new short[] { 1 });
			antennas.getAntenna((short) 1).setIsMaxRxSensitivity(false);
			antennas.getAntenna((short) 1).setIsMaxTxPower(false);
			antennas.getAntenna((short) 1).setTxPowerinDbm(25.0);
			antennas.getAntenna((short) 1).setRxSensitivityinDbm(-70);

			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					// RSSI
					if (RorP == 'R') {
						for (Tag t : tags) {
							signal.add(t.getPeakRssiInDbm());
						}
					}
					// Phase
					else if (RorP == 'P') {
						for (Tag t : tags) {
							signal.add(2 * Math.PI - t.getPhaseAngleInRadians());
						}
					}
				}
			});

			//System.out.println("Applying Settings");
			reader.applySettings(settings);

			//System.out.println("Starting");
			reader.start();

			System.out.println("敲击回车停止采集.");
			// Scanner s = new Scanner(System.in);
			sc.nextLine();

			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		double sum = 0;
		for (double sig : signal) {
			sum += sig;
		}
		// 返回采集点的信号信息
		return sum / signal.size();
	}
	
	/**
	 * 使用singleRead连续采集多组数据，如从60cm处以5cm为间隔采集到90cm处。
	 * 由用户根据提示输入采集的信号类型以及每次的采集位置。
	 * @param hostname 阅读器ip
	 * @param targetEpc 标签EPC
	 * @param filename 要写入的文件名（只写单个文件）
	 */
	public static void useSingleRead(String hostname,String targetEpc,String filename){
		ArrayList<double[]> RSSI = new ArrayList<double[]>();//存储（距离，RSSI）
		ArrayList<double[]> Phase = new ArrayList<double[]>();//存储（距离，Phase）
		//存储文件的列表
		ArrayList<String> write = new ArrayList<String>();
		Scanner sc = new Scanner(System.in);
		
		int total = 0;
		char RorP = 'R';//默认为RSSI
		
		System.out.println("采集RSSI输入R 采集相位输入P");
		
		String s = sc.nextLine();
		switch(s){
		case "R":
			System.out.println("开始采集RSSI");
			System.out.println("输入采集位置总数：");
			total = sc.nextInt();
			// 连续输入位置 调用采集信号函数（SingleRead）存入数组
			for(int i = 0;i<total;i++){
				System.out.println("确定标签位置后，输入当前测试距离(cm):");
				int tempInt = sc.nextInt();sc.nextLine();
				double[] temp = new double[2];
				temp[0] = tempInt;
				temp[1] = RFRead.singleRead(hostname,targetEpc,RorP);
				RSSI.add(temp);
			}
			System.out.println("数据采集完毕，结果如下：");
			for(int i = 0;i<RSSI.size();i++){
				System.out.print(RSSI.get(i)[0]+"cm:"+RSSI.get(i)[1]+"\t");
			}
			System.out.println();
			
			// 写文件
			for (int i = 0; i < RSSI.size(); i++) {
				double[] dtemp = RSSI.get(i);
				write.add(String.format("%.0f", dtemp[0])+","+dtemp[1]);// 写入距离，RSSI值
			}
			RFRead.writefileM(filename+"_R", write);
			
			System.out.println("请于当前目录下查看保存的"+filename+"+R.csv数据文件");
			break;
			
		case "P":
			RorP = 'P';
			System.out.println("开始采集相位");
			System.out.println("输入采集位置总数：");
			total = sc.nextInt();
		  // 连续输入位置 调用采集的函数存入二维数组 然后写入文件
			for(int i = 0;i<total;i++){
				System.out.println("确定标签位置/cm(或角度/°)后，输入当前测试距离/cm(或角度/°):");
				int tempInt = sc.nextInt();sc.nextLine();
				double[] temp = new double[2];
				temp[0] = tempInt;
				temp[1] = RFRead.singleRead(hostname,targetEpc,RorP);
				Phase.add(temp);
			}
			System.out.println("数据采集完毕，结果如下：");
			for(int i = 0;i<Phase.size();i++){
				System.out.print(Phase.get(i)[0]+"cm:"+Phase.get(i)[1]+"\t");
			}
			System.out.println();
			
			// 写文件
			for (int i = 0; i < Phase.size(); i++) {
				double[] dtemp = Phase.get(i);
				write.add(String.format("%.0f", dtemp[0])+","+dtemp[1]);// 写入距离，Phase值
			}
			RFRead.writefileM(filename+"_P", write);
			
			System.out.println("请于当前目录下查看保存的"+filename+"_P.csv的数据文件");
			break;
			
		default:
				System.out.println("输入有误！");
		}
	}
	
	/**
	 * 静态扫描定位(LANDMARC)采集两个天线获取的RSSI 
	 * 数据采集以敲击回车结束，取采集时间段内的平均值作为结果
	 * @param hostname 阅读器的ip
	 * @param targetEpc 标签掩码
	 * @param num 参考标签总数  
	 * @param filename 要写入的文件名
	 * @return 两天线采集到的RSSI信号值double[2][num] 第一维分别表示1、2号天线的数据 第二维依次是所有的参考标签和待定位标签的RSSI
	 *
	 */
	 public static double[][] read2RSSI(String hostname,String targetEpc,int num,String filename){
	 /** 设定目标标签mask*/
	 //String targetEpc = "20190706000000000000";//按照日期设定
	 /** 存储两个天线获取的RSSI */
	 ArrayList<ArrayList<Double>> RSSI1 = new
	 ArrayList<ArrayList<Double>>();//1号天线采集的标签RSSI
	 ArrayList<ArrayList<Double>> RSSI2 = new
	 ArrayList<ArrayList<Double>>();//2号天线采集的标签RSSI
	 for (int i=0;i<201;i++){
	 ArrayList<Double> tempa = new ArrayList<Double>();
	 ArrayList<Double> tempb = new ArrayList<Double>();
	 RSSI1.add(tempa);
	 RSSI2.add(tempb);
	 }
	 try {
	
	 //String hostname = "192.168.100.169";//填写阅读器的IP
	 ImpinjReader reader = new ImpinjReader();
	
	 System.out.println("Connecting");
	 reader.connect(hostname);
	
	 Settings settings = reader.queryDefaultSettings();
	
	 ReportConfig report = settings.getReport();
	 report.setIncludeAntennaPortNumber(true);
	 report.setIncludePeakRssi(true);
	 report.setIncludePhaseAngle(true);
	 report.setIncludeFirstSeenTime(true);
	 report.setIncludeChannel(true);
	 report.setMode(ReportMode.Individual);//每个标签单独作为一个report返回
	
	 settings.setReaderMode(ReaderMode.AutoSetDenseReader);
	
	 //设置标签读取过滤器
	 TagFilter t1 = settings.getFilters().getTagFilter1();
	 t1.setBitCount(80);//掩码位数
	 t1.setBitPointer(BitPointers.Epc);
	 t1.setMemoryBank(MemoryBank.Epc);
	 t1.setFilterOp(TagFilterOp.Match);
	 t1.setTagMask(targetEpc);//80位10字节
	
	 settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
	 System.out.println("Matching 1st 80 bits of epc " + targetEpc);
	
	 // set some special settings for antenna 1
	 AntennaConfigGroup antennas = settings.getAntennas();
	 antennas.disableAll();
	 antennas.enableById(new short[] { 1,2 });
	 antennas.getAntenna((short) 1).setIsMaxRxSensitivity(false);
	 antennas.getAntenna((short) 1).setIsMaxTxPower(false);
	 antennas.getAntenna((short) 1).setTxPowerinDbm(25.0);
	 antennas.getAntenna((short) 1).setRxSensitivityinDbm(-70);
	
	 antennas.getAntenna((short) 2).setIsMaxRxSensitivity(false);
	 antennas.getAntenna((short) 2).setIsMaxTxPower(false);
	 antennas.getAntenna((short) 2).setTxPowerinDbm(25.0);
	 antennas.getAntenna((short) 2).setRxSensitivityinDbm(-70);
	
	 reader.setTagReportListener(new TagReportListenerImplementation(){
	
	 @Override
	 public void onTagReported(ImpinjReader reader0, TagReport report0) {
	
		 List<Tag> tags = report0.getTags();
		 for (Tag t : tags) {
			 System.out.print(t.getAntennaPortNumber()+":"+t.getEpc()+":"+t.getPeakRssiInDbm());//【】【】【】【】
			 // 取EPC末4位
			 int subEPC = Integer.parseInt(t.getEpc().toString().substring(25, 29));
			 //1号天线
			 if(t.getAntennaPortNumber()==1){
				 RSSI1.get(subEPC).add(t.getPeakRssiInDbm());//EPC号对应的标签增加RSSI数据值
			 }
			 //2号天线
			 else if(t.getAntennaPortNumber()==2){
				 RSSI2.get(subEPC).add(t.getPeakRssiInDbm());
			 }
			 System.out.println();
		 	}
		 }
	 });
	
	 System.out.println("敲击回车开始采集");
	 Scanner s = new Scanner(System.in);
	 s.nextLine();
	 
	 //System.out.println("Applying Settings");
	 reader.applySettings(settings);
	
	 System.out.println("Starting");
	 reader.start();
	
	 System.out.println("敲击回车结束采集");
	 s = new Scanner(System.in);
	 s.nextLine();
	
	 reader.stop();
	 reader.disconnect();
	
		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		// 返回获得的平均值
		double[][] RSSI = new double[2][num];
		for (int i = 0; i < num; i++) {
			double sum = 0;
			// 1天线的每个标签一段时间内的所有RSSI值求平均
			for (int j = 0; j < RSSI1.get(i).size(); j++) {
				sum += RSSI1.get(i).get(j);
			}
			RSSI[0][i] = sum / RSSI1.get(i).size();
			// 2天线的每个标签一段时间内的所有RSSI值求平均
			sum = 0;
			for (int j = 0; j < RSSI2.get(i).size(); j++) {
				sum += RSSI2.get(i).get(j);
			}
			RSSI[1][i] = sum / RSSI2.get(i).size();
		}
		//写文件 
		//filename = "LANDMARC";
		ArrayList<String> write  = new ArrayList<String>();
		for (int j = 0; j < RSSI[0].length; j++) {
			write.add((j)+","+String.format("%.4f", RSSI[0][j])+","+String.format("%.4f", RSSI[1][j]));// 写入标签id,天线1测得的RSSI,天线2测得的RSSI
		}
		RFRead.writefileM(filename, write);
		System.out.println("请于当前目录下查看保存的"+filename+".csv数据文件");
		return RSSI;

	 }
	 
	 /**
	  * 使用Read2RSSI，根据参考标签和待定位标签的信号值计算出待定位标签的位置
	  * @param referLoc 参考标签的xy坐标 从下标1开始存
	  * @param hostname 阅读器的ip
	  * @param targetEpc 标签掩码（待定位标签EPC末尾编号为0，其余参考标签EPC末尾编号从1开始）
	  * @param num 参考标签和待定位标签总数
	  * @param filename 要写入的文件名
	  * @return 待定位标签的xy坐标 
	  */
	 public static double[] LANDMARC(double[][] referLoc,String hostname,String targetEpc,int num,String filename){
		 //获取待定位标签和参考标签的RSSI
		 double RSSI[][] = RFRead.read2RSSI(hostname,targetEpc,num,filename);
		 System.out.println("测得的RSSI为:");
		 for(int i=0;i<RSSI.length;i++){
			 System.out.println("天线"+(i+1)+":");
			 for(int j=0;j<RSSI[i].length;j++){
				 System.out.print("标签"+j+":"+RSSI[i][j]+" ");
			 }
			 System.out.println();
		 }
		 //待定位标签的RSSI
		 double S1 = RSSI[0][0];
		 double S2 = RSSI[1][0];
		 //计算待定位标签和参考标签RSSI之间的欧氏距离
		 double E[] = new double[num];
		 System.out.println("依次输出所有与待定位标签之间的欧氏距离");
		 for(int i = 1;i<num;i++){
			 E[i] = Math.sqrt(Math.pow(S1-RSSI[0][i],2)+Math.pow(S2-RSSI[1][i],2));
			 System.out.println(E[i]);//依次输出所有欧氏距离
		 }
		 //重新整理E，按照key排序 最前面4个即为E最小的4个标签 key=Ej,value=标签号
		 TreeMap<Double,Integer> tagE = new TreeMap<Double,Integer>();
		 for (int i = 1;i<num;i++){
			 tagE.put(E[i],i);
		 }
		 //按顺序取出标4个标签号tag4[i] i=0:3
		 int[] tag4 = new int[4];
		 Collection<Integer> col = tagE.values();
		 Iterator<Integer> it = col.iterator();
		 System.out.println("由小到大输出欧氏距离最近的四个标签号");
		 for(int i=0;i<4;i++){
			 tag4[i]=it.next();
			 System.out.println(tag4[i]);//由小到大输出欧氏距离最近的四个标签号
		 }
		 //计算4个最近才考点的权重W[i] i=0:3
		 double[] W = new double[4];
		 double Wm = 1.0/Math.pow(E[tag4[0]],2)+1.0/Math.pow(E[tag4[1]],2)+1.0/Math.pow(E[tag4[2]],2)+1.0/Math.pow(E[tag4[3]],2);
		 System.out.println("依次输出权重");
		 for(int i=0;i<4;i++){
			 W[i] = 1.0/Math.pow(E[tag4[i]],2)/Wm;
			 System.out.println(W[i]);//依次输出权重
		 }
		 double[] xy = new double[2];
		 xy[0] = W[0]*referLoc[tag4[0]][0]+W[1]*referLoc[tag4[1]][0]+W[2]*referLoc[tag4[2]][0]+W[3]*referLoc[tag4[3]][0];
		 xy[1] = W[0]*referLoc[tag4[0]][1]+W[1]*referLoc[tag4[1]][1]+W[2]*referLoc[tag4[2]][1]+W[3]*referLoc[tag4[3]][1];
		 
		 return xy;
	 }
	 	
	 /**
	  * 选择阅读模式
	  */
	 protected static String chooseReaderMode(String readerModel,String mode) {
		 String chooseMode = "";
		 //SpeedwayR220
			if (readerModel.equals("SpeedwayR220")){
				if(mode.equals("AutoSetDenseReader")) {
					chooseMode = "AutoSetDenseReader";
				}
				else if(mode.equals("DenseReaderM4")){
					chooseMode = "DenseReaderM4";
				}
				else if(mode.equals("DenseReaderM4Two")) {
					chooseMode = "DenseReaderM4Two";
				}
				else if(mode.equals("DenseReaderM8")) {
					chooseMode = "DenseReaderM8";
				}
				else{
					System.out.println("阅读器型号："+readerModel+"，不支持当前所设阅读模式"
						+mode+"（或阅读模式输入有误），已自动设置为AutoSetDenseReader模式。");
					chooseMode = "AutoSetDenseReader";
				}
			}
			//SpeedwayR420	
			//AutoSetDenseReader AutoSetDenseReaderDeepScan AutoSetStaticDRM AutoSetStaticFast
			//MaxThroughput Hybrid 
			else if(readerModel.equals("SpeedwayR420")) {
				if(mode.equals("AutoSetDenseReader")) {
					chooseMode = "AutoSetDenseReader";
				}
				else if(mode.equals("AutoSetDenseReaderDeepScan")){
					chooseMode = "AutoSetDenseReaderDeepScan";
				}
				else if(mode.equals("AutoSetStaticDRM")){
					chooseMode = "AutoSetStaticDRM";
				}
				else if(mode.equals("AutoSetStaticFast")){
					chooseMode = "AutoSetStaticFast";
				}
				else if(mode.equals("DenseReaderM4")){
					chooseMode = "DenseReaderM4";
				}
				else if(mode.equals("DenseReaderM4Two")) {
					chooseMode = "DenseReaderM4Two";
				}
				else if(mode.equals("DenseReaderM8")) {
					chooseMode = "DenseReaderM8";
				}
				else if(mode.equals("MaxThroughput")) {
					chooseMode = "MaxThroughput";
				}
				else if(mode.equals("Hybrid")) {
					chooseMode = "Hybrid";
				}
				else{
					System.out.println("阅读器型号："+readerModel+"，不支持当前所设阅读模式"
						+mode+"（或阅读模式输入有误），已自动设置为AutoSetDenseReader模式。");
					chooseMode = "AutoSetDenseReader";
				}
			}
			return chooseMode;
	 }
	
	 /**
	  * 连续扫描标签，采集信息包括 RSSI、相位、时间、天线号、频率等。
	  * 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
	  * 用户通过【敲击回车】控制采集时长。 根据用户提供的文件名写【单个】文件。 无返回值
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * 
	  */
	 public static void readAll(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,
			 int RSSI, int phase, int time,int portN, int freq) {
		ArrayList<String> TagInfoArray = new ArrayList<String>();
		try {
			ImpinjReader reader = new ImpinjReader();
			System.out.println("Connecting");
			reader.connect(hostname);
			
			FeatureSet f = reader.queryFeatureSet();
			String readerModel = f.getReaderModel().toString();//SpeedwayR420 SpeedwayR220
			
			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回
			
			mode = chooseReaderMode(readerModel,mode);
			settings.setReaderMode(ReaderMode.valueOf(mode));
			//设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length()*4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st "+targetmask.length()*4+" bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(port);
			for (short portID : port) {
				antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
				antennas.getAntenna((short) portID).setIsMaxTxPower(false);
				antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
				antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
			}
			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {
						
						if (label.containsKey(t.getEpc().toString())) {
							// if (t.isRfPhaseAnglePresent() && t.isPeakRssiInDbmPresent()) {
							String tempAdd = "" + label.get(t.getEpc().toString());
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if(output) {
								System.out.println(tempAdd);
							}
						}
						else {
							String tempAdd = "0";
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if(output) {
								System.out.println(tempAdd);
							}
						}
					}
				}
			});
			//System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("在控制台敲击回车开始扫描.");
			System.out.println("再次敲击回车结束扫描.");
			Scanner s = new Scanner(System.in);
			s.nextLine();		
			//System.out.println("Starting");
			reader.start();
			s = new Scanner(System.in);
			s.nextLine();

			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}
		// 写文件
		RFRead.writefileS(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的"+filename+".csv数据文件");
	}
	 
	 /**
	  * 连续扫描标签， 采集信息包括 RSSI、相位、时间、天线号、频率等。
	  * 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
	  * 用户通过【敲击回车】控制采集时长。 根据用户提供的文件名可以连续写【多个】文件。 无返回值
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * 
	  */
	 public static void readAllF(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,
			 int RSSI, int phase, int time,int portN, int freq) {
		 
		ArrayList<String> TagInfoArray = new ArrayList<String>();
		try {
			ImpinjReader reader = new ImpinjReader();
			System.out.println("Connecting");
			reader.connect(hostname);
			
			FeatureSet f = reader.queryFeatureSet();
			String readerModel = f.getReaderModel().toString();//SpeedwayR420 SpeedwayR220
			
			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回
			
			mode = chooseReaderMode(readerModel,mode);
			settings.setReaderMode(ReaderMode.valueOf(mode));

			// 设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length()*4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);// 80位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st "+targetmask.length()*4+" bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(port);
			for (short portID : port) {
				antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
				antennas.getAntenna((short) portID).setIsMaxTxPower(false);
				antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
				antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
			}
			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {
						
						if (label.containsKey(t.getEpc().toString())) {
							// if (t.isRfPhaseAnglePresent() && t.isPeakRssiInDbmPresent()) {
							String tempAdd = "" + label.get(t.getEpc().toString());
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if(output) {
								System.out.println(tempAdd);
							}
						}
						else {
							String tempAdd = "0";
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if(output) {
								System.out.println(tempAdd);
							}
						}
					}
				}
			});
			//System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("在控制台敲击回车开始扫描.");
			System.out.println("再次敲击回车结束扫描.");
			Scanner s = new Scanner(System.in);
			s.nextLine();		
			//System.out.println("Starting");
			reader.start();
			s = new Scanner(System.in);
			s.nextLine();

			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		// 写文件
		RFRead.writefileM(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的"+filename+".csv数据文件");

	}
			
	 /**
	  * 连续扫描标签， 采集信息包括 RSSI、相位、时间、天线号、频率等。
	  * 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
	  * 用户通过【传入参数】控制采集时长。 根据用户提供的文件名写【单个】文件。 无返回值
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param howlong 采集时长
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * 
	  */
	 public static void readAllT(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,int howlong,
			 int RSSI, int phase, int time,int portN, int freq) {

		ArrayList<String> TagInfoArray = new ArrayList<String>();
		try {
			ImpinjReader reader = new ImpinjReader();
			System.out.println("Connecting");
			reader.connect(hostname);

			FeatureSet f = reader.queryFeatureSet();
			String readerModel = f.getReaderModel().toString();// SpeedwayR420 SpeedwayR220

			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回

			mode = chooseReaderMode(readerModel, mode);
			settings.setReaderMode(ReaderMode.valueOf(mode));

			// 设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length() * 4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);// 80位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st " + targetmask.length() * 4 + " bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(port);
			for (short portID : port) {
				antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
				antennas.getAntenna((short) portID).setIsMaxTxPower(false);
				antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
				antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
			}
			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {

						if (label.containsKey(t.getEpc().toString())) {
							// if (t.isRfPhaseAnglePresent() && t.isPeakRssiInDbmPresent()) {
							String tempAdd = "" + label.get(t.getEpc().toString());
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						} else {
							String tempAdd = "0";
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						}
					}
				}
			});
			// System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("在控制台敲击回车开始扫描");
			Scanner s = new Scanner(System.in);
			s = new Scanner(System.in);
			s.nextLine();
			//System.out.println("Starting");
			reader.start();
			Thread.sleep(howlong);
			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		// 写文件
		RFRead.writefileS(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的"+filename+".csv数据文件");

	}

	 /**
	  * 连续扫描标签， 采集信息包括 RSSI、相位、时间、天线号、频率等。
	  * 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
	  * 用户通过【传入参数】控制采集时长。 根据用户提供的文件名可以连续写【多个】文件。 无返回值
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param howlong 采集时长
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * 
	  */
	 public static void readAllTF(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,int howlong,
			 int RSSI, int phase, int time,int portN, int freq) {
		 
		ArrayList<String> TagInfoArray = new ArrayList<String>();
		try {
			ImpinjReader reader = new ImpinjReader();
			System.out.println("Connecting");
			reader.connect(hostname);

			FeatureSet f = reader.queryFeatureSet();
			String readerModel = f.getReaderModel().toString();// SpeedwayR420 SpeedwayR220

			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回

			mode = chooseReaderMode(readerModel, mode);
			settings.setReaderMode(ReaderMode.valueOf(mode));

			// 设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length() * 4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);// 80位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st " + targetmask.length() * 4 + " bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(port);
			for (short portID : port) {
				antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
				antennas.getAntenna((short) portID).setIsMaxTxPower(false);
				antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
				antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
			}
			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {

						if (label.containsKey(t.getEpc().toString())) {
							// if (t.isRfPhaseAnglePresent() && t.isPeakRssiInDbmPresent()) {
							String tempAdd = "" + label.get(t.getEpc().toString());
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						} else {
							String tempAdd = "0";
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						}
					}
				}
			});
			// System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("在控制台敲击回车开始扫描");
			Scanner s = new Scanner(System.in);
			s = new Scanner(System.in);
			s.nextLine();
			// System.out.println("Starting");
			reader.start();
			Thread.sleep(howlong);
			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		// 写文件
		RFRead.writefileM(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的"+filename+".csv数据文件");

	}
 

	/**
	  * 连续扫描标签， 采集信息包括 RSSI、相位、时间、天线号、频率、阅读率等。
	  * 标签个数通过参数设定。 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
	  * 用户通过敲击回车控制采集时长。 根据用户提供的文件名写单个文件。 
	  * 同名函数，增加一位参数表示阅读率，返回值为计算的阅读率。
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * @param readRate 1表示需要统计阅读率
	  * @return 每个标签的阅读率
	  * 
	  */
	public static int[] readAll(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,
			 int RSSI, int phase, int time,int portN, int freq, int readRate) {
		//统计阅读率
		Collection<Integer> values = label.values();
		Iterator<Integer> iter = values.iterator();
		int max = 0,tempmax = 0;
		while(iter.hasNext()) {
			tempmax=iter.next();
			if(tempmax<0) {
				System.out.println("tagID不能为负数！");
				System.exit(0);
			}
			if (tempmax>max) {
				max = tempmax;
			}
		}
		max++;
		int[] rate = new int[max];
		ArrayList<String> TagInfoArray = new ArrayList<String>();
		try {
			ImpinjReader reader = new ImpinjReader();
			System.out.println("Connecting");
			reader.connect(hostname);
			
			FeatureSet f = reader.queryFeatureSet();
			String readerModel = f.getReaderModel().toString();//SpeedwayR420 SpeedwayR220
			
			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回
			
			mode = chooseReaderMode(readerModel,mode);
			settings.setReaderMode(ReaderMode.valueOf(mode));
			//设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length()*4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);// 80位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st "+targetmask.length()*4+" bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(port);
			for (short portID : port) {
				antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
				antennas.getAntenna((short) portID).setIsMaxTxPower(false);
				antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
				antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
			}
			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {
						
						if (label.containsKey(t.getEpc().toString())) {
							rate[label.get(t.getEpc().toString())]++;
							// if (t.isRfPhaseAnglePresent() && t.isPeakRssiInDbmPresent()) {
							String tempAdd = "" + label.get(t.getEpc().toString());
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if(output) {
								System.out.println(tempAdd);
							}
						}
						else {
							String tempAdd = "0";
							rate[0]++;
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if(output) {
								System.out.println(tempAdd);
							}
						}
					}
				}
			});
			//System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("在控制台敲击回车开始扫描.");
			System.out.println("再次敲击回车结束扫描.");
			Scanner s = new Scanner(System.in);
			s.nextLine();		
			//System.out.println("Starting");
			reader.start();
			s = new Scanner(System.in);
			s.nextLine();

			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		// 写文件
		RFRead.writefileS(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的"+filename+".csv文件");
		return rate;
	}
	
	/**
	  * 连续扫描标签， 采集信息包括 RSSI、相位、时间、天线号、频率、阅读率等。
	  * 标签个数通过参数设定。 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
	  * 用户通过敲击回车控制采集时长。 根据用户提供的文件名写【多个】文件。 
	  * 同名函数，增加一位参数表示阅读率，返回值为计算的阅读率。
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * @param readRate 1表示需要统计阅读率
	  * @return 每个标签的阅读率
	  * 
	  */
	public static int[] readAllF(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,
			 int RSSI, int phase, int time,int portN, int freq, int readRate) {
		
		// 统计阅读率
		Collection<Integer> values = label.values();
		Iterator<Integer> iter = values.iterator();
		int max = 0,tempmax = 0;
		while(iter.hasNext()) {
			tempmax=iter.next();
			if(tempmax<0) {
				System.out.println("tagID不能为负数！");
				System.exit(0);
			}
			if (tempmax>max) {
				max = tempmax;
			}
		}
		max++;
		int[] rate = new int[max];
		ArrayList<String> TagInfoArray = new ArrayList<String>();
		try {
			ImpinjReader reader = new ImpinjReader();
			System.out.println("Connecting");
			reader.connect(hostname);

			FeatureSet f = reader.queryFeatureSet();
			String readerModel = f.getReaderModel().toString();// SpeedwayR420 SpeedwayR220

			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回

			mode = chooseReaderMode(readerModel, mode);
			settings.setReaderMode(ReaderMode.valueOf(mode));
			// 设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length() * 4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);// 80位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st " + targetmask.length() * 4 + " bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(port);
			for (short portID : port) {
				antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
				antennas.getAntenna((short) portID).setIsMaxTxPower(false);
				antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
				antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
			}
			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {

						if (label.containsKey(t.getEpc().toString())) {
							rate[label.get(t.getEpc().toString())]++;
							// if (t.isRfPhaseAnglePresent() && t.isPeakRssiInDbmPresent()) {
							String tempAdd = "" + label.get(t.getEpc().toString());
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						} else {
							String tempAdd = "0";
							rate[0]++;
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						}
					}
				}
			});
			// System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("在控制台敲击回车开始扫描.");
			System.out.println("再次敲击回车结束扫描.");
			Scanner s = new Scanner(System.in);
			s.nextLine();		
			//System.out.println("Starting");
			reader.start();
			s = new Scanner(System.in);
			s.nextLine();

			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		// 写文件
		RFRead.writefileM(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的"+filename+".csv文件");
		return rate;
	}
	

	/**
	 	* 连续扫描标签， 采集信息包括 RSSI、相位、时间、天线号、频率、阅读率等。
	 	* 标签个数通过参数设定。 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
		* 用户通过【传入参数】控制采集时长。 根据用户提供的文件名写【单个】文件。 
		* 同名函数， 增加一位参数表示阅读率， 返回值为计算的阅读率。
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param howlong 采集时长
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * @param readRate 1表示需要统计阅读率
	  * @return 每个标签的阅读率
		*/
	public static int[] readAllT(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,int howlong,
			 int RSSI, int phase, int time,int portN, int freq, int readRate) {
		
		// 统计阅读率
			Collection<Integer> values = label.values();
			Iterator<Integer> iter = values.iterator();
			int max = 0,tempmax = 0;
			while(iter.hasNext()) {
				tempmax=iter.next();
				if(tempmax<0) {
					System.out.println("tagID不能为负数！");
					System.exit(0);
				}
				if (tempmax>max) {
					max = tempmax;
				}
			}
			max++;
			int[] rate = new int[max];
			
			ArrayList<String> TagInfoArray = new ArrayList<String>();
			try {
				ImpinjReader reader = new ImpinjReader();
				System.out.println("Connecting");
				reader.connect(hostname);

				FeatureSet f = reader.queryFeatureSet();
				String readerModel = f.getReaderModel().toString();// SpeedwayR420 SpeedwayR220

				Settings settings = reader.queryDefaultSettings();

				ReportConfig report = settings.getReport();
				report.setIncludeAntennaPortNumber(true);
				report.setIncludePeakRssi(true);
				report.setIncludePhaseAngle(true);
				report.setIncludeFirstSeenTime(true);
				report.setIncludeChannel(true);
				report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回

				mode = chooseReaderMode(readerModel, mode);
				settings.setReaderMode(ReaderMode.valueOf(mode));

				// 设置标签读取过滤器settings
				TagFilter t1 = settings.getFilters().getTagFilter1();
				t1.setBitCount(targetmask.length() * 4);// 掩码位数
				t1.setBitPointer(BitPointers.Epc);
				t1.setMemoryBank(MemoryBank.Epc);
				t1.setFilterOp(TagFilterOp.Match);
				t1.setTagMask(targetmask);// 80位 12字节

				settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
				System.out.println("Matching 1st " + targetmask.length() * 4 + " bits of epc " + targetmask);

				// set some special settings for antenna 1
				AntennaConfigGroup antennas = settings.getAntennas();
				antennas.disableAll();
				antennas.enableById(port);
				for (short portID : port) {
					antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
					antennas.getAntenna((short) portID).setIsMaxTxPower(false);
					antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
					antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
				}
				reader.setTagReportListener(new TagReportListenerImplementation() {

					@Override
					public void onTagReported(ImpinjReader reader0, TagReport report0) {

						List<Tag> tags = report0.getTags();
						for (Tag t : tags) {

							if (label.containsKey(t.getEpc().toString())) {
								rate[label.get(t.getEpc().toString())]++;
								String tempAdd = "" + label.get(t.getEpc().toString());
								if (RSSI == 1) {
									tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
								}
								if (phase == 1) {
									tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
								}
								if (time == 1) {
									tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
								}
								if (portN == 1) {
									tempAdd = tempAdd + "," + t.getAntennaPortNumber();
								}
								if (freq == 1) {
									tempAdd = tempAdd + "," + t.getChannelInMhz();
								}
								TagInfoArray.add(tempAdd);
								if (output) {
									System.out.println(tempAdd);
								}
							} else {
								String tempAdd = "0";
								rate[0]++;
								if (RSSI == 1) {
									tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
								}
								if (phase == 1) {
									tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
								}
								if (time == 1) {
									tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
								}
								if (portN == 1) {
									tempAdd = tempAdd + "," + t.getAntennaPortNumber();
								}
								if (freq == 1) {
									tempAdd = tempAdd + "," + t.getChannelInMhz();
								}
								TagInfoArray.add(tempAdd);
								if (output) {
									System.out.println(tempAdd);
								}
							}
						}
					}
				});
				// System.out.println("Applying Settings");
				reader.applySettings(settings);

				System.out.println("在控制台敲击回车开始扫描");
				Scanner s = new Scanner(System.in);
				s = new Scanner(System.in);
				s.nextLine();
				//System.out.println("Starting");
				reader.start();
				Thread.sleep(howlong);
				reader.stop();
				reader.disconnect();

			} catch (OctaneSdkException ex) {
				System.out.println(ex.getMessage());
			} catch (Exception ex) {
				System.out.println(ex.getMessage());
				ex.printStackTrace(System.out);
			}

		// 写文件
		RFRead.writefileS(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的" + filename + ".csv数据文件");

		return rate;
	}

	/**
		* 连续扫描标签， 采集信息包括 RSSI、相位、时间、天线号、频率、阅读率等。
		* 标签个数通过参数设定。 通过对表示信号值的参数位置 0 或置 1 来定义是否需要采集该信号值。
		* 用户通过【传入参数】控制采集时长。 根据用户提供的文件名可以连续写【多个】文件。
		* 同名函数， 增加一位参数表示阅读率， 返回值为计算的阅读率。
	  * @param hostname 阅读器的IP
	  * @param port 天线端口号（可选择多个端口）
	  * @param TxPowerinDbm 传输功率设定（Dbm）范围：10~32.5
	  * @param RxSensitivityinDbm 接收灵敏度设定
	  * @param mode 阅读模式选择
	  * @param label 记录EPC对应的标签ID
	  * @param targetmask 标签掩码
	  * @param filename 要保存的文件名
	  * @param output 选择是否将读取到的标签信息输出到控制台
	  * @param howlong 采集时长
	  * @param RSSI 用0和1表示是否需要读取RSSI
	  * @param phase 用0和1表示是否需要读取相位
	  * @param time 用0和1表示是否需要读取采集时刻
	  * @param portN 用0和1表示是否需要读取天线号
	  * @param freq 用0和1表示是否需要读取频率
	  * @param readRate 1表示需要统计阅读率
	  * @return 每个标签的阅读率
		*  
		*/
	public static int[] readAllTF(String hostname, short[] port,double TxPowerinDbm,double RxSensitivityinDbm,
			 String mode,HashMap<String, Integer> label, String targetmask, String filename, boolean output,int howlong,
			 int RSSI, int phase, int time,int portN, int freq, int readRate) {
		// 统计阅读率
		Collection<Integer> values = label.values();
		Iterator<Integer> iter = values.iterator();
		int max = 0,tempmax = 0;
		while(iter.hasNext()) {
			tempmax=iter.next();
			if(tempmax<0) {
				System.out.println("tagID不能为负数！");
				System.exit(0);
			}
			if (tempmax>max) {
				max = tempmax;
			}
		}
		max++;
		int[] rate = new int[max];
		
		ArrayList<String> TagInfoArray = new ArrayList<String>();
		try {
			ImpinjReader reader = new ImpinjReader();
			System.out.println("Connecting");
			reader.connect(hostname);

			FeatureSet f = reader.queryFeatureSet();
			String readerModel = f.getReaderModel().toString();// SpeedwayR420 SpeedwayR220

			Settings settings = reader.queryDefaultSettings();

			ReportConfig report = settings.getReport();
			report.setIncludeAntennaPortNumber(true);
			report.setIncludePeakRssi(true);
			report.setIncludePhaseAngle(true);
			report.setIncludeFirstSeenTime(true);
			report.setIncludeChannel(true);
			report.setMode(ReportMode.Individual);// 每个标签单独作为一个report返回

			mode = chooseReaderMode(readerModel, mode);
			settings.setReaderMode(ReaderMode.valueOf(mode));

			// 设置标签读取过滤器settings
			TagFilter t1 = settings.getFilters().getTagFilter1();
			t1.setBitCount(targetmask.length() * 4);// 掩码位数
			t1.setBitPointer(BitPointers.Epc);
			t1.setMemoryBank(MemoryBank.Epc);
			t1.setFilterOp(TagFilterOp.Match);
			t1.setTagMask(targetmask);// 80位 12字节

			settings.getFilters().setMode(TagFilterMode.OnlyFilter1);
			System.out.println("Matching 1st " + targetmask.length() * 4 + " bits of epc " + targetmask);

			// set some special settings for antenna 1
			AntennaConfigGroup antennas = settings.getAntennas();
			antennas.disableAll();
			antennas.enableById(port);
			for (short portID : port) {
				antennas.getAntenna((short) portID).setIsMaxRxSensitivity(false);
				antennas.getAntenna((short) portID).setIsMaxTxPower(false);
				antennas.getAntenna((short) portID).setTxPowerinDbm(TxPowerinDbm);
				antennas.getAntenna((short) portID).setRxSensitivityinDbm(RxSensitivityinDbm);
			}
			reader.setTagReportListener(new TagReportListenerImplementation() {

				@Override
				public void onTagReported(ImpinjReader reader0, TagReport report0) {

					List<Tag> tags = report0.getTags();
					for (Tag t : tags) {

						if (label.containsKey(t.getEpc().toString())) {
							rate[label.get(t.getEpc().toString())]++;
							String tempAdd = "" + label.get(t.getEpc().toString());
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						} else {
							String tempAdd = "0";
							rate[0]++;
							if (RSSI == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPeakRssiInDbm());
							}
							if (phase == 1) {
								tempAdd = tempAdd + "," + Double.toString(t.getPhaseAngleInRadians());
							}
							if (time == 1) {
								tempAdd = tempAdd + "," + t.getFirstSeenTime().ToString();
							}
							if (portN == 1) {
								tempAdd = tempAdd + "," + t.getAntennaPortNumber();
							}
							if (freq == 1) {
								tempAdd = tempAdd + "," + t.getChannelInMhz();
							}
							TagInfoArray.add(tempAdd);
							if (output) {
								System.out.println(tempAdd);
							}
						}
					}
				}
			});
			// System.out.println("Applying Settings");
			reader.applySettings(settings);

			System.out.println("在控制台敲击回车开始扫描");
			Scanner s = new Scanner(System.in);
			s = new Scanner(System.in);
			s.nextLine();
			//System.out.println("Starting");
			reader.start();
			Thread.sleep(howlong);
			reader.stop();
			reader.disconnect();

		} catch (OctaneSdkException ex) {
			System.out.println(ex.getMessage());
		} catch (Exception ex) {
			System.out.println(ex.getMessage());
			ex.printStackTrace(System.out);
		}

		// 写文件
		RFRead.writefileM(filename, TagInfoArray);
		System.out.println("请于当前目录下查看保存的" + filename + ".csv数据文件");

		return rate;
	}
	
	/**
	 * 写单个的文件
	 * @param filename 传入的文件名
	 * @param content 每一行的内容
	 * @param <T> 字符串或数字 
	 */
	public static <T> void writefileS(String filename,ArrayList<T> content){
		File file = new File(filename + ".csv");

		BufferedWriter bw = null;
		try {
			bw = new BufferedWriter(new FileWriter(file));
			for (int i = 0; i < content.size(); i++) {
				String temp = (String) content.get(i);
				bw.write(temp); // 写入所有的EPC,RSSI,Phase,Hz,time,天线号
			//	bw.write("," + (i + 1));// ,id
				bw.newLine();
			}
			bw.flush();
			bw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

/**
 * 连续写多个文件
 * @param filename 传入的文件名
 * @param content 每一行的内容
 * @param <T> 字符或数字 
 */
	public static <T> void writefileM(String filename, ArrayList<T> content) {
		String fileFolderName = ".";
		int fileCount = 0;
		int step = 1;

		File fileFind = new File(fileFolderName);
		File[] fileArray = fileFind.listFiles();
		for (int i = 0; i < fileArray.length; i++) {
			if (fileArray[i].isFile() && fileArray[i].getName().startsWith(filename)) {
				fileCount += step;
			}
		}
		File file = new File(fileFolderName + "/" + filename + "_" + fileCount + ".csv");

		BufferedWriter bw = null;
		try {
			bw = new BufferedWriter(new FileWriter(file));
			for (int i = 0; i < content.size(); i++) {
				String temp = content.get(i).toString();
				bw.write(temp); // 写入所有的EPC,RSSI,Phase,Hz,time,天线号
				// bw.write("," + (i + 1));// ,id
				bw.newLine();
			}
			bw.flush();
			bw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
