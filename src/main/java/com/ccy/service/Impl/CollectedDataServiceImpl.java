package com.ccy.service.Impl;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.ccy.bean.CollectParameter;
import com.ccy.bean.Parameter;
import com.ccy.bean.Subsystem;
import com.ccy.netty.CCYCollectedData;
import com.ccy.netty.SensorValue;
import com.ccy.service.SubsystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ccy.dao.CollectedDataDao;
import com.ccy.dao.CollectorDao;
import com.ccy.dto.CollectedValue;
import com.ccy.service.CollectedDataService;

@Service
public class CollectedDataServiceImpl implements CollectedDataService {

	private static final String DataTableNamePrefix = "collecteddata";

	@Autowired
	private CollectorDao collectorDao;

	@Autowired
	private SubsystemService subsystemService;

	@Autowired
	private CollectedDataDao collectedDataDao;

	/**
	 * 缓存最新的采集数据
	 */
	private Map<String, CollectedValue> topValues =
			new ConcurrentHashMap<String, CollectedValue>();


	private Map<String, String> tableNames = new ConcurrentHashMap<String, String>(160);

	private Map<Integer, Map<Integer, CollectParameter>> cpsMap
			= new ConcurrentHashMap<Integer, Map<Integer, CollectParameter>>(8);

	public boolean add(CCYCollectedData collectedData) {
		if (collectedData == null)
			throw new NullPointerException("collectedData");

		int collectorId = collectedData.collectorNo;
		List<SensorValue> sValues = collectedData.sensorValues;

		String tableName = getTableName(collectorId);
		List<String> fields = new ArrayList<String>(sValues.size());
		List<Double> values = new ArrayList<Double>(sValues.size());
		Map<Integer, CollectParameter> cpMap;
		if (cpsMap.containsKey(collectorId)) {
			cpMap = cpsMap.get(collectorId);
		} else {
			cpMap = getMapFromList(
					collectorDao.getCollectParameters1(collectorId));
			if (cpMap != null) {
				cpsMap.put(collectorId, cpMap);
			}
		}

		CollectParameter cp;
		StringBuilder stringBuilder = new StringBuilder();
		for (SensorValue sv : sValues) {
			stringBuilder.append('p');
			if (cpMap.containsKey(sv.paramNo)) {
				/** 拼接字段名，并组织提交到到的参数 */
				cp = cpMap.get(sv.paramNo);
				stringBuilder.append(cp.getSubsystemId())
						.append('_').append(cp.getParameterId());
				values.add((double) sv.value);
				fields.add(stringBuilder.toString());
			}

			/** 缓存最新数据 */
			topValues.put(stringBuilder.toString(), new CollectedValue(sv.value));

			stringBuilder.delete(0, stringBuilder.length());
		}
		return tableName != null && collectedDataDao.add(tableName, fields, values) > 0;
	}


	public Map<Integer, CollectedValue> getTopOnes(int subsystemId) {
		Subsystem subsystem = subsystemService.getById(subsystemId);
		if (subsystem == null) return null;

		Map<Integer, CollectedValue> values =
				new HashMap<Integer, CollectedValue>(32);
		for (Parameter p : subsystem.getParameters()) {
			CollectedValue cv = getTopOne(subsystemId, p.getId());
			if (cv != null)
				values.put(p.getId(), cv);
		}
		return values;
	}

	public CollectedValue getTopOne(int subsystemId, int parameterId) {
		CollectedValue collectedValue;
		String key = "p" + subsystemId + '_' + parameterId;
		if (topValues.containsKey(key)) {
			collectedValue = topValues.get(key);
		} else {
			collectedValue = collectedDataDao.getTopOn(
					getTableName(subsystemId, parameterId), key);
			if (collectedValue == null) {
				return null;
			}
			topValues.put(key, collectedValue);
		}
		return collectedValue;
	}

	/**
	 *
	 * @param subsystemId
	 * @param parameterId
	 * @param beginTime
	 * @return
	 */
	public List<CollectedValue> getAfter(int subsystemId, int parameterId, Date beginTime) {
		String tableName = getTableName(subsystemId, parameterId);
		String fieldName = getFieldName(subsystemId, parameterId);
		return tableName == null || fieldName == null ? null
				: collectedDataDao.getAfter(tableName, fieldName, beginTime);
	}

	public List<CollectedValue> getAfter(int deviceId, int subsystemId, int parameterId,
								  Date beginTime){
		String tableName = getTableName(deviceId);
		String fieldName = getFieldName(subsystemId, parameterId);
		return tableName == null || fieldName == null ? null
				: collectedDataDao.getAfter(tableName, fieldName, beginTime);
	}

	/**
	 *
	 * @param subsystemId
	 * @param parameterId
	 * @param beginTime
	 * @param endTime
	 * @return
	 */
	public List<CollectedValue> getBetween(int subsystemId, int parameterId, Date beginTime, Date endTime) {
		String tableName = getTableName(subsystemId, parameterId);
		String fieldName = getFieldName(subsystemId, parameterId);
		return tableName == null || fieldName == null ? null
				: collectedDataDao.getBetween(tableName, fieldName, beginTime, endTime);
	}

	public List<CollectedValue> getBetween(int deviceId, int subsystemId, int parameterId,
									Date beginTime, Date endTime){
		String tableName = getTableName(deviceId);
		String fieldName = getFieldName(subsystemId, parameterId);
		return tableName == null || fieldName == null ? null
				: collectedDataDao.getBetween(tableName, fieldName, beginTime, endTime);
	}

	/**
	 *
	 * @param subsystemId
	 * @param parameterId
	 * @return
	 */
	public CollectedValue getCurrentValueById(int subsystemId, int parameterId) {
		String tableName = getTableName(subsystemId, parameterId);
		String fieldName = getFieldName(subsystemId, parameterId);
		return tableName== null || fieldName== null ? null
				: collectedDataDao.getCurrentValue(tableName,fieldName);
	}

	private String getTableName(int deviceId) {
		return DataTableNamePrefix + deviceId;
	}

	private String getTableName(int subsystemId, int parameterId) {
		String tableName = null;
		String key = subsystemId + "_" + parameterId;
		if (tableNames.containsKey(key)) {
			tableName = tableNames.get(key);
		} else {
			CollectParameter cp = collectorDao.getBySubsystemIdAndParameterId(subsystemId, parameterId);
			if (cp != null) {
				tableName = getTableName(cp.getDeviceId());
				if (tableName != null) {
					tableNames.put(key, tableName);
				}
			}
		}
		return tableName;
	}

	private String getFieldName(int subsystemId, int parameterId) {
		return "p" + subsystemId + "_" + parameterId;
	}

	private Map<Integer, CollectParameter> getMapFromList(List<CollectParameter> cps){
		if(cps == null || cps.size() == 0)
			return null;

		Map<Integer, CollectParameter>  cpMap =
				new TreeMap<Integer, CollectParameter>(new Comparator<Integer>() {
			public int compare(Integer o1, Integer o2) {
				return o1.intValue() - o2.intValue();
			}
		});
		for(CollectParameter cp : cps){
			cpMap.put(cp.getParameterIndex(), cp);
		}
		return cpMap;
	}
}
