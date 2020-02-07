package com.robot.mvc.main;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.http.HttpStatus;
import com.robot.RobotContext;
import com.robot.adapter.model.RobotStateModel;
import com.robot.mvc.core.exceptions.RobotException;
import com.robot.mvc.core.interfaces.*;
import com.robot.mvc.core.telegram.*;
import com.robot.mvc.model.RepeatSendModel;
import com.robot.mvc.utils.RobotUtil;
import com.robot.mvc.utils.ToolsKit;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * 调度分发工厂
 * 根据协议指令中的车辆ID及指令动作，将协议分发到对应的service里的method。
 * 所以在Service里必须要实现对应指令动作的方法。
 *
 * @author Laotang
 */
public class DispatchFactory {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchFactory.class);

    /**协议解码器*/
    private static IProtocolMatcher protocolMatcher;
    /**重复发送对象*/
    private static IRepeatSend repeatSend;
    /**操作超时时长*/
    private static Integer REQUEST_TIME_OUT = 3000;

    static {
        initComponents();
    }

    /**
     * 系统接收到车辆或工作站发起的业务协议字符串
     * @param message 协议内容
     */
    public static void onIncomingTelegram(String message) {
        LOG.info("onIncomingTelegram: {}", message);
        if (null == protocolMatcher) {
            initComponents();
        }
        try {
            IProtocol protocol = protocolMatcher.encode(message);
            // 如果返回的code在Map集合里存在，则视为由RequestKit发送请求的响应，将响应协议对象设置到对应的Map集合里，并退出
            if (RobotContext.getResponseProtocolMap().containsKey(protocol.getCode())) {
                LinkedBlockingQueue<IProtocol> protocolQueue = RobotContext.getResponseProtocolMap().get(protocol.getCode());
                protocolQueue.add(protocol);
                RobotContext.getResponseProtocolMap().put(protocol.getCode(), protocolQueue);
                return;
            }
            BusinessRequest businessRequest = new BusinessRequest(message, protocol);
            // 如果在BusinessRequest里的adapter为null，则说明提交的协议字符串不属于车辆移动协议
            businessRequest.setAdapter(RobotContext.getAdapter(protocol.getDeviceId()));
            IResponse response = dispatchHandler(businessRequest, new BaseResponse(businessRequest));
            // 如果状态等于200并且是需要进行到适配器进行操作的
            // isNeedAdapterOperation在BaseService里设置，默认为false;
            if (response.getStatus() == HttpStatus.HTTP_OK && response.isNeedAdapterOperation()) {
                // 调用通讯适配器方法，更新车辆位置显示或调用工站动作
                RobotStateModel robotStateModel = response.getRobotStateModel();
                if (ToolsKit.isEmpty(robotStateModel) || ToolsKit.isEmpty(robotStateModel.getCurrentPosition())) {
                    throw new RobotException("robotStateModel对象或更新位置不能为空");
                }
                try {
                    RobotContext.getAdapter(response.getDeviceId()).onIncomingTelegram(robotStateModel);
                } catch (RobotException re) {
                    //TODO 抛出异常，说明提交的卡号与队列中的第1位元素不一致，可作立即停车处理
                    LOG.info(re.getMessage(), re);
                    RobotContext.getRobotComponents().stopVehicle(protocol);
                }
            }
        } catch (Exception e) {
            LOG.error("分发处理接收到的业务协议字符串时出错: {}, {}", e.getMessage(), e);
            return;
        }
    }

    /**
     * 分发处理接收到的工站动作任务请求
     * 调度系统发起的请求
     *
     * @param request ActionRequest
     */
    public static IResponse dispatch(ActionRequest request, IResponse response) {
        return dispatchHandler(request, response);
    }

    /**
     * 分发处理接收到的移动请求
     * 调度系统发起的请求
     *
     * @param request MoveRequest
     */
    public static IResponse dispatch(MoveRequest request) {
        return dispatchHandler(request, new BaseResponse(request));
    }

    private static IResponse dispatchHandler(IRequest request, IResponse response) {
        try {
            FutureTask<IResponse> futureTask = (FutureTask<IResponse>) ThreadUtil.execAsync(new RequestTask(request, response));
            response = futureTask.get(REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);
            if (response.getStatus() == HttpStatus.HTTP_OK) {
                //是同一单元的请求响应且需要发送的响应
                if (response.isResponseTo(request) && response.isNeedSend()) {
                    // 将Response对象放入重发队列，确保消息发送到车辆
                    repeatSend.add(response);
                    // 正确的响应才发送到车辆或设备
                    request.getAdapter().sendTelegram(response);
                }
            }
        } catch (TimeoutException te) {
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
            IProtocol protocol = request.getProtocol();
            String errorMsg = protocol.getDeviceId() + "进行" + protocol.getCmdKey() + "操作超时" + te.getMessage();
            response.write(errorMsg);
            LOG.error(errorMsg, te);
        } catch (Exception e) {
            //设置为错误500状态
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
            String errorMsg = "";
            if (RobotUtil.isMoveRequest(request)) {
                errorMsg = "分发移动请求时发生异常:";
            } else if (RobotUtil.isActionRequest(request)) {
                errorMsg = "分发工站动作请求时发生异常:";
            } else {
                errorMsg = "分发处理接收到的业务协议字符串时异常:";
            }
            errorMsg += e.getMessage();
            response.write(errorMsg);
            LOG.error(errorMsg, e);
        }
        return response;
    }

    /**
     * 初始化协议解析对象
     */
    private static void initComponents() {

        IComponents agvComponents = RobotContext.getRobotComponents();
        if (ToolsKit.isEmpty(agvComponents)) {
            throw new RobotException("OpenAGV组件对象不能为空,请先实现IComponents接口，并在Duang.java里设置setComponents方法");
        }

        protocolMatcher = agvComponents.getProtocolMatcher();
        if (ToolsKit.isEmpty(protocolMatcher)) {
            throw new RobotException("协议解码器不能为空，请先实现IComponents接口里的getProtocolDecode方法");
        }

        repeatSend = agvComponents.getRepeatSend();
        if (ToolsKit.isEmpty(repeatSend)) {
            throw new RobotException("重复发送不能为空，请先实现IComponents接口里的getRepeatSend方法");
        }

    }
}