package com.robot.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.robot.RobotContext;
import com.robot.adapter.constants.RobotConstants;
import com.robot.adapter.exchange.AdapterComponentsFactory;
import com.robot.adapter.exchange.RobotAdapterDescription;
import com.robot.adapter.model.DeviceAddress;
import com.robot.adapter.model.RobotProcessModel;
import com.robot.contrib.netty.comm.NetChannelType;
import com.robot.mvc.core.exceptions.RobotException;
import com.robot.utils.RobotUtil;
import com.robot.utils.ToolsKit;
import org.opentcs.components.kernel.services.DispatcherService;
import org.opentcs.components.kernel.services.TCSObjectService;
import org.opentcs.components.kernel.services.TransportOrderService;
import org.opentcs.data.model.Vehicle;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;
import org.opentcs.drivers.vehicle.VehicleCommAdapterDescription;
import org.opentcs.drivers.vehicle.VehicleCommAdapterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.opentcs.util.Assertions.checkInRange;

/***
 * Robot通讯适配器工厂
 */
public class RobotCommAdapterFactory implements VehicleCommAdapterFactory {

    private static final Logger LOG = LoggerFactory.getLogger(RobotCommAdapterFactory.class);

    private static final String  DEVICE_ADDRESS = "deviceAddress";
    private static String XML_FILENAME = "";
    /**
     * 适配器组件工厂
     */
    private final AdapterComponentsFactory componentsFactory;
    /**
     * 组件是否已经初始化
     */
    private boolean initialized;
    /***
     * 通讯渠道类型
     */
    private NetChannelType channelType;


    /**
     * 大杀器
     */
    private TCSObjectService tcsObjectService;
    /**
     * 订单服务
     */
    private TransportOrderService transportOrderService;
    /**
     * 分发任务服务
     */
    private DispatcherService dispatcherService;
    /**
     * 创建组件工厂
     *
     * @param componentsFactory 创建特定于通信适配器的组件工厂
     */
    @Inject
    public RobotCommAdapterFactory(AdapterComponentsFactory componentsFactory,
                                   TCSObjectService tcsObjectService,
                                   TransportOrderService transportOrderService,
                                   DispatcherService dispatcherService) {
        this.componentsFactory = requireNonNull(componentsFactory, "组件工厂对象不能为空");
        this.tcsObjectService = requireNonNull(tcsObjectService, "tcsObjectService");
        this.transportOrderService = transportOrderService;
        this.dispatcherService = dispatcherService;
    }

    @Override
    public void initialize() {
        if (initialized) {
            LOG.info("Robot适配器工厂重复初始化");
            return;
        }
        channelType = RobotUtil.getNetChannelType();
        initialized = true;
        RobotContext.setTCSObjectService(tcsObjectService);
        RobotContext.setTransportOrderService(transportOrderService);
        RobotContext.setDispatcherService(dispatcherService);
        LOG.info("Robot适配器工厂初始化完成");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!initialized) {
            LOG.warn("Robot适配器工厂没有初始化");
            return;
        }
        initialized = false;
        LOG.info("Robot适配器工厂终止");
    }

    /**
     * 通讯适配器名称
     *
     * @return
     */
    @Override
    public VehicleCommAdapterDescription getDescription() {
        return new RobotAdapterDescription();
    }

    @Override
    @Deprecated
    public String getAdapterDescription() {
        return getDescription().getDescription();
    }

    @Override
    public boolean providesAdapterFor(Vehicle vehicle) {
        requireNonNull(vehicle, "车辆不能为空");
        if (NetChannelType.TCP.equals(channelType) || NetChannelType.UDP.equals(channelType)) {
            if (ToolsKit.isEmpty(vehicle.getProperty(RobotConstants.HOST_FIELD))) {
                throw new RobotException(vehicle.getName() + "车辆host没有设置");
            }

            if (ToolsKit.isEmpty(vehicle.getProperty(RobotConstants.PORT_FIELD))) {
                throw new RobotException(vehicle.getName() + "车辆port没有设置");
            }
            try {
                //设置端口范围
                checkInRange(Integer.parseInt(vehicle.getProperty(RobotConstants.PORT_FIELD)),
                        1024,
                        65535);
            } catch (IllegalArgumentException exc) {
                throw new RobotException(vehicle.getName() + "端口范围值须在" + 1024 + "~" + 65535 + "之间");
            }
        }

        return true;
    }

    @Override
    public VehicleCommAdapter getAdapterFor(Vehicle vehicle) {
        requireNonNull(vehicle, "车辆不能为空");
        try {
            // TCP/UDP通讯模式下，车辆必须要设置链接地址及端口
            if (!providesAdapterFor(vehicle)) {
                return null;
            }
            RobotCommAdapter adapter = componentsFactory.createCommAdapter(vehicle);
//            String xmlFileName = adapter.getPlantModelService().getModelName();
//            if (!xmlFileName.equals(XML_FILENAME)) {
//                ElementKit.duang().clear();
//                XML_FILENAME = xmlFileName;
//            }
            RobotProcessModel processModel = adapter.getProcessModel();
            if (NetChannelType.TCP.equals(channelType) || NetChannelType.UDP.equals(channelType)) {
                processModel.setVehicleHost(vehicle.getProperty(RobotConstants.HOST_FIELD));
                processModel.setVehiclePort(Integer.parseInt(vehicle.getProperty(RobotConstants.PORT_FIELD)));
                processModel.setDeviceAddress(getDeviceAddressList(vehicle.getProperty(DEVICE_ADDRESS)));
            }
            // 加入到缓存集合
            RobotContext.getAdapterMap().put(processModel.getName(), adapter);
            return adapter;
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * 取设备地址，写在车辆属性表中
     * @param addressJson
     * @return
     */
    private List<DeviceAddress> getDeviceAddressList(String addressJson) {
        if (ToolsKit.isEmpty(addressJson)) {
            return new ArrayList<>();
        }
        TypeReference typeReference = new TypeReference<List<DeviceAddress>>(){};
        try {
            List<DeviceAddress> deviceAddressList =  ToolsKit.jsonParseArray(addressJson, typeReference);
            return  (ToolsKit.isEmpty(deviceAddressList)) ? new ArrayList<>() : deviceAddressList;
        } catch (Exception e) {
            LOG.error(e.getMessage() ,e);
            return null;
        }
    }
}
