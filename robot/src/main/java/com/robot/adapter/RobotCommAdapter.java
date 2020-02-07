package com.robot.adapter;

import cn.hutool.core.thread.ThreadUtil;
import com.google.inject.assistedinject.Assisted;
import com.robot.RobotContext;
import com.robot.adapter.enumes.LoadAction;
import com.robot.adapter.enumes.LoadState;
import com.robot.adapter.exchange.AdapterComponentsFactory;
import com.robot.adapter.model.RobotProcessModel;
import com.robot.adapter.model.RobotStateModel;
import com.robot.adapter.model.RobotVehicleModelTO;
import com.robot.adapter.task.MoveCommandListener;
import com.robot.adapter.task.MoveRequesterTask;
import com.robot.config.RobotConfiguration;
import com.robot.contrib.netty.comm.IChannelManager;
import com.robot.contrib.netty.comm.VehicleChannelManager;
import com.robot.mvc.core.exceptions.RobotException;
import com.robot.mvc.core.interfaces.IAction;
import com.robot.mvc.core.interfaces.IRequest;
import com.robot.mvc.core.interfaces.IResponse;
import com.robot.mvc.core.telegram.ITelegramSender;
import com.robot.mvc.utils.RobotUtil;
import com.robot.mvc.utils.ToolsKit;
import org.opentcs.components.kernel.services.TCSObjectService;
import org.opentcs.contrib.tcp.netty.ConnectionEventListener;
import org.opentcs.customizations.kernel.KernelExecutor;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.drivers.vehicle.BasicVehicleCommAdapter;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.util.ExplainedBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.Objects.requireNonNull;

/**
 * Agv通讯适配器
 * 一台车辆对应一个适配器
 *
 * @author Laotang
 */
public class RobotCommAdapter
        extends BasicVehicleCommAdapter
        implements ConnectionEventListener<RobotStateModel>, ITelegramSender {

    private static final Logger LOG = LoggerFactory.getLogger(RobotCommAdapter.class);

    /**大杀器*/
    private TCSObjectService tcsObjectService;
    /**执行器*/
    private ExecutorService kernelExecutor;
    /**适配器组件工厂*/
    private AdapterComponentsFactory componentsFactory;
    /**配置文件类*/
    private RobotConfiguration configuration;
    /**车辆*/
    private Vehicle vehicle;
    /**
     * 移动命令定时发送监听器
     */
    private MoveCommandListener moveCommandListener;
    /**移动请求任务定时器，一车辆实例一次*/
    private MoveRequesterTask moveRequesterTask;
    /**
     * 临时移动命令队列
     */
    private Queue<MovementCommand> tempCommandQueue;
    /**移动命令队列*/
    private Queue<MovementCommand> movementCommandQueue;

    /**车辆网络连接管理器*/
    private IChannelManager<IRequest, IResponse> vehicleChannelManager;
    /**运行方式，以服务器方式运行还是客户端方式链接车辆*/
    private String runType;

    @Inject
    public RobotCommAdapter(AdapterComponentsFactory componentsFactory,
                            TCSObjectService tcsObjectService,
                            RobotConfiguration configuration,
                            @Assisted Vehicle vehicle,
                            @KernelExecutor ExecutorService kernelExecutor) {
        super(new RobotProcessModel(vehicle),
                configuration.commandQueueCapacity(),
                configuration.sentQueueCapacity(),
                configuration.rechargeOperation());

        this.tcsObjectService = requireNonNull(tcsObjectService, "tcsObjectService");
        this.vehicle = requireNonNull(vehicle, "vehicle");
        this.configuration = requireNonNull(configuration, "configuration");
        this.componentsFactory = requireNonNull(componentsFactory, "componentsFactory");
        this.kernelExecutor = requireNonNull(kernelExecutor, "kernelExecutor");
        /**移动命令队列*/
        this.tempCommandQueue = new LinkedBlockingQueue<>();
        this.movementCommandQueue = new LinkedBlockingQueue<>();
        RobotContext.setTCSObjectService(tcsObjectService);
    }

    /**
     * 初始化适配器
     */
    @Override
    public void initialize() {
        if (isInitialized()) {
            LOG.info("车辆[{}]已初始化通讯管理器，请勿重复初始化", getName());
            return;
        }
        super.initialize();
        runType = RobotUtil.getRunType();
        LOG.info("车辆[{}]完成Robot适配器初始化完成，系统运行类型为[{}]", getName(), runType.toLowerCase());
    }

    /**
     * 开启通讯适配器
     */
    @Override
    public synchronized void enable() {
        if (isEnabled()) {
            LOG.info("车辆[{}]已开启通讯适配器，请勿重复开启", getName());
            return;
        }
        //每开启一个车辆就启动一个定时监听器
        moveCommandListener = new MoveCommandListener(this);
        moveRequesterTask = new MoveRequesterTask(moveCommandListener);
        moveRequesterTask.enable(getName());
        // 初始化车辆渠道管理器
        if (null == vehicleChannelManager) {
            vehicleChannelManager = VehicleChannelManager.getChannelManager(this);
            if (!vehicleChannelManager.isInitialized()) {
                vehicleChannelManager.initialize();
            }
        }
        super.enable();
        LOG.info("成功开启车辆[{}]通讯适配器", getName());
    }

    public synchronized void trigger() {

    }

    /**
     * 连接车辆，在BasicVehicleCommAdapter里enable方法下回调
     */
    @Override
    protected void connectVehicle() {
        if (null == vehicleChannelManager) {
            LOG.warn("车辆[{}]通讯渠道管理器不存在", getName());
            return;
        }
        // 根据车辆设置的host与port，连接车辆
        String host = RobotUtil.getHost(getName());
        int port = RobotUtil.getPort(getName());
        try {
            vehicleChannelManager.connect(host, port);
            LOG.info("LOG.info(vehicleChannelManager.hashCode: {}", vehicleChannelManager.hashCode());
            LOG.info("连接车辆[{}]成功: [{}]", getName(), (host + ":" + port));
        } catch (RobotException e) {
            LOG.error("连接车辆[{}]时发生异常: {}", getName(), e.getMessage());
            throw e;
        }
    }

    /**断开车辆连接*/
    @Override
    protected void disconnectVehicle() {
        if (null == vehicleChannelManager) {
            LOG.warn("车辆[{}]通讯渠道管理器不存在.", getName());
            return;
        }
        try {
            vehicleChannelManager.disconnect();
            // 清除与该车辆相关的参数
            getSentQueue().clear();
            getCommandQueue().clear();
            vehicleChannelManager = null;
            moveCommandListener = null;
            moveRequesterTask.disable(getName());
            LOG.info("成功断开车辆[{}]通讯适配器链接", getName());
        } catch (Exception e) {
            LOG.error("断开车辆[{}]通讯适配器链接时发生异常: {}", getName(), e.getMessage());
            throw e;
        }
    }

    /**判断车辆是否已经连接*/
    @Override
    protected boolean isVehicleConnected() {
        return null != vehicleChannelManager && vehicleChannelManager.isConnected();
    }

    /**是否执行进程操作，车辆移动命令发送前检查*/
    @Nonnull
    @Override
    public ExplainedBoolean canProcess(@Nonnull List<String> operations) {
        requireNonNull(operations, "operations");
        boolean canProcess = true;
        String reason = "";

        if(!isEnabled()) {
            canProcess = false;
            reason= "通讯适配器没有开启";
        }

        if(canProcess && !isVehicleConnected()) {
            canProcess = false;
            reason = "车辆可能没有连接";
        }

        String vehicleStateName = getProcessModel().getVehicleState().name();
        if(canProcess &&
                LoadState.UNKNOWN.name().equalsIgnoreCase(vehicleStateName)) {
            canProcess = false;
            reason = "车辆负载状态未知";
        }

        boolean loaded = LoadState.FULL.name().equalsIgnoreCase(vehicleStateName);
        final Iterator<String> iterator = operations.iterator();
        while (canProcess && iterator.hasNext()) {
            final String nextOp = iterator.next();
            if(loaded) {
                if (LoadAction.LOAD.equalsIgnoreCase(nextOp)) {
                    canProcess = false;
                    reason = "不能重复装载";
                } else if (LoadAction.UNLOAD.equalsIgnoreCase(nextOp)) {
                    loaded = false;
                } else if (DriveOrder.Destination.OP_PARK.equalsIgnoreCase(nextOp)) {
                    canProcess = false;
                    reason = "车辆在装载状态下不应该停车";
                } else if (LoadAction.CHARGE.equalsIgnoreCase(nextOp)) {
                    canProcess = false;
                    reason = "车辆在装载状态下不应该充电";
                }
            } else if (LoadAction.LOAD.equalsIgnoreCase(nextOp)){
                loaded = true;
            } else if(LoadAction.UNLOAD.equalsIgnoreCase(nextOp)){
                canProcess = false;
                reason = "未加载时无法卸载";
            }
        }
        return new ExplainedBoolean(canProcess, reason);
    }

    /**
     * 发送移动命令
     * 当有车辆需要进行交通管制时，会自动将可以移动的MovementCommand对象回调到sendCommand方法，
     * 告诉可以使用的MovementCommand对象，可能回调多次或一次，回调次数是根据OpenTCS的交通管制算法将可运行的路径
     * 添加到getCommandQueue()队列，队列再poll到方法
     * 利用这个回调发送移动命令，将多次回调的MovementCommand再次组装成List集合，
     * 再交由业务逻辑部份处理，生成相应的协议内容发送到车辆
     *
     * @param cmd 移动命令对象
     * @throws IllegalArgumentException
     */
    @Override
    public void sendCommand(MovementCommand cmd) throws IllegalArgumentException {
        cmd = requireNonNull(cmd, "MovementCommand is null");
        // 添加到队列
        tempCommandQueue.add(cmd);
        /**
         * 监听器引用队列，处理后发送协议，由于监听定时器是由指定时间间隔执行一次，所以这间隔时间不能设置太少
         * 如果设置间隔时间太少，则有可能导致movementCommandQueue添加队列时没有全部添加完成就执行了发送。
         * 目前默认是1秒执行一次，理论上来说，时间是足够的
         */
        moveCommandListener.quoteCommand(tempCommandQueue);
    }


    /**进程消息*/
    @Override
    public void processMessage(@Nullable Object message) {
        LOG.info("processMessage: {}", message);
    }

    /**取车辆进程模型*/
    @Override
    public final RobotProcessModel getProcessModel() {
        return (RobotProcessModel) super.getProcessModel();
    }

    /**
     * 取移动命令队列
     * @return
     */
    public Queue<MovementCommand> getMovementCommandQueue() {
        return movementCommandQueue;
    }


    //*********************************ConnectionEventListener*************************************/

    /**
     * 接收到报文信息，此次报文指令应是车辆上报卡号的指令协议
     * 上报卡号后，opentcs也应该同步更新UI界面以显示车辆最新位置
     *
     * @param stateModel 状态对象
     */
    @Override
    public void onIncomingTelegram(RobotStateModel stateModel) {
        requireNonNull(stateModel, "stateModel");
        // 车辆状态设置为不空闲
        getProcessModel().setVehicleIdle(false);
        // 当前位置
        String currentPosition = stateModel.getCurrentPosition();
        if (ToolsKit.isEmpty(currentPosition)) {
            throw new RobotException("更新位置不能为空！");
        }
        try {
            /**
             *每上报一个卡号，比较上报的卡号与队列中的第1位元素是否匹配，匹配则将第一位的元素移除，否则抛出异常，发送停车协议
             * 匹配规则：比较上报的卡号与队列中的第一位是否相等 ,如果不一致，则抛出异常，让业务逻辑代码作后续处理，例如立即停车
             */
            if (!getMovementCommandQueue().isEmpty()) {
                // 比较卡号是否与队列中的第1位元素一致
                MovementCommand command = getMovementCommandQueue().peek();
                if (null != command) {
                    String sourcePointName = command.getStep().getSourcePoint().getName();
                    if (null != sourcePointName && sourcePointName.equals(currentPosition)) {
                        getMovementCommandQueue().remove();
                        LOG.info("车辆[{}]接收到的上报位置[{}]与车辆移动指令队列中的第1位元素一致，移除后继续执行操作!", getName(), currentPosition);
                    } else {
                        throw new RobotException("车辆[" + getName() + "]接收到的上报位置[" + currentPosition + "]与车辆移动指令队列中的第1位元素不一致，请检查！");
                    }
                }
            }
            // 根据上报的卡号，更新位置
            getProcessModel().setVehiclePosition(currentPosition);
            // 更新为最新状态
            getProcessModel().setVehicleState(RobotUtil.translateVehicleState(stateModel.getOperatingState()));
            //  检查移动订单是否完成
            checkOrderFinished(stateModel);
        } catch (Exception e) {
            LOG.error("vehicle[" + getName() + "] adapter onIncomingTelegram is exception: " + e.getMessage(), e);
            throw new RobotException(e.getMessage(), e);
        }
    }

    /**
     * 检查移动订单是否完成
     * @param stateModel
     */
    private void checkOrderFinished(RobotStateModel stateModel) {
        MovementCommand cmd = getSentQueue().peek();
        String operation = cmd.getOperation();
        // 不是NOP，是最后一条指令并且自定义动作组合里包含该动作名称
        if (null != cmd &&
                !cmd.isWithoutOperation() &&
                cmd.isFinalMovement() &&
                ToolsKit.isNotEmpty(operation)) {
            // 如果动作指令操作未运行则可以运行
            LOG.info("车辆[{}]在[{}]位置上准备执行工站[{}]指令集", getName(), cmd.getStep().getSourcePoint().getName(), operation);
            executeLocationActions(cmd, getName(), operation);
        } else {
            LOG.info("车辆[{}]移动到点[{}]成功", getName(), cmd.getStep().getDestinationPoint().getName());
            MovementCommand curCommand = getSentQueue().poll();
            if (null != cmd && null != curCommand && cmd.equals(curCommand)) {
                getProcessModel().commandExecuted(curCommand);
            }
        }
    }

    /**
     * 执行工站动作指令
     *
     * @param cmd
     * @param adapterName 通讯适配器名称
     * @param operation   工站动作指令集名称
     */
    private void executeLocationActions(MovementCommand cmd, String adapterName, String operation) {

        if (null == cmd && !operation.equalsIgnoreCase(cmd.getOperation())) {
            throw new RobotException("车辆[" + adapterName + "]通讯适配器移动命令为null或工站指令集名称[" + operation + "!=" + cmd.getOperation() + "]" );
        }

        if (!RobotUtil.isContainActionsKey(operation)) {
            throw new RobotException("调度系统没有发现指定的工站动作名称: " + operation + ", 请检查是否正确设置，工站名称须唯一且一致");
        }
        if (!isEnabled()) {
            LOG.error("车辆[{}]通讯适配器没开启，请先开启！", adapterName);
            return;
        }
        LOG.info("车辆[{}]开始执行自定义指令集合[{}]操作", adapterName, operation);
        try {
            //设置为执行状态
            getProcessModel().setVehicleState(Vehicle.State.EXECUTING);
            // 设置为允许单步执行，即等待自定义命令执行完成或某一指令取消单步操作模式后，再发送移动车辆命令。
            getProcessModel().setSingleStepModeEnabled(true);
            // 线程执行自定义指令队列
            ThreadUtil.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        IAction action = RobotUtil.getLocationAction(operation);
                        if (ToolsKit.isNotEmpty(action)) {
                            // 调用BaseActions里execute方法
                            action.execute();
                        } else {
                            LOG.info("根据[{}]查找不到对应的动作指令处理类", operation);
                        }
                    } catch (Exception e) {
                        LOG.error("执行自定义动作组合指令时出错: " + e.getMessage(), e);
                    }
                }
            });
        } catch (Exception e) {
            throw new RobotException(e.getMessage(), e);
        }
    }

    /***
     * BaseActios执行指令集完成后，调用该方法，执行下一个订单
     */
    public void executeNextMoveCmd() {
        LOG.info("成功执行工站指令集，检查是否有下一订单，如有则继续执行");
        RobotProcessModel processModel = getProcessModel();
        //车辆设置为空闲状态，执行下一个移动指令
        getProcessModel().setVehicleState(Vehicle.State.IDLE);
        // 取消单步执行状态
        getProcessModel().setSingleStepModeEnabled(false);
        // 移除移动命令
        MovementCommand cmd = getSentQueue().poll();
        // 如果不为空且是最终移动命令，则执行下一个订单
        if (null != cmd && cmd.isFinalMovement()) {
            processModel.commandExecuted(cmd);
        }
    }

    /**链接车辆*/
    @Override
    public void onConnect() {
        if (!isEnabled()) {
            return;
        }
        getProcessModel().setCommAdapterConnected(true);
        LOG.debug("车辆[{}]连接成功", getName());
    }

    /***/
    @Override
    public void onFailedConnectionAttempt() {
        if (!isEnabled()) {
            return;
        }
        getProcessModel().setCommAdapterConnected(false);
        if (isEnabled() && getProcessModel().isReconnectingOnConnectionLoss()) {
            vehicleChannelManager.scheduleConnect(RobotUtil.getHost(getName()), RobotUtil.getPort(getName()), getProcessModel().getReconnectDelay());
        }
    }

    /**断开链接*/
    @Override
    public void onDisconnect() {
        LOG.debug("车辆[{}]断开连接成功", getName());
        getProcessModel().setCommAdapterConnected(false);
        getProcessModel().setVehicleIdle(true);
        getProcessModel().setVehicleState(Vehicle.State.UNKNOWN);
        if (isEnabled() && getProcessModel().isReconnectingOnConnectionLoss()) {
            vehicleChannelManager.scheduleConnect(RobotUtil.getHost(getName()), RobotUtil.getPort(getName()), getProcessModel().getReconnectDelay());
        }
    }

    /**空闲*/
    @Override
    public void onIdle() {
        LOG.debug("车辆[{}]空闲", getName());
        getProcessModel().setVehicleIdle(true);
        // 如果支持重连则的车辆空闲时断开连接
        if (isEnabled() && getProcessModel().isDisconnectingOnVehicleIdle()) {
            LOG.debug("车辆[{}]开启了空闲时断开连接", getName());
            disconnectVehicle();
        }
    }

    /**
     * 覆盖实现
     * 用于将值传递到控制中心的自定义面板
     * 启动时，面板点击更新后均会触发
     *
     * @return
     */
    @Override
    protected RobotVehicleModelTO createCustomTransferableProcessModel() {
        // 发送到其他软件（如控制中心或工厂概览）时，添加车辆的附加信息
        return new RobotVehicleModelTO()
                .setSingleStepModeEnabled(getProcessModel().isSingleStepModeEnabled())
                .setLoadOperation(getProcessModel().getLoadOperation())
                .setUnloadOperation(getProcessModel().getUnloadOperation())
                .setMaxAcceleration(getProcessModel().getMaxAcceleration())
                .setMaxDeceleration(getProcessModel().getMaxDecceleration())
                .setMaxFwdVelocity(getProcessModel().getMaxFwdVelocity())
                .setMaxRevVelocity(getProcessModel().getMaxRevVelocity())
                .setOperatingTime(getProcessModel().getOperatingTime())
                .setVehiclePaused(getProcessModel().isVehiclePaused());
    }

    //*********************************ITelegramSender*************************************/
    /**
     * 发送报文
     * @param response 电报响应对象
     */
    @Override
    public void sendTelegram(IResponse response) {
        vehicleChannelManager.send(response);
    }
}