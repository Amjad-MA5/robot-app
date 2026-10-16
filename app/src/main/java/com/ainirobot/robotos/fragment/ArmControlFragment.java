/*
 *  Copyright (C) 2026 OrionStar Technology Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ainirobot.robotos.fragment;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.ainirobot.coreservice.client.Definition;
import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.listener.CommandListener;
import com.ainirobot.coreservice.client.StatusListener;
import com.ainirobot.robotos.LogTools;
import com.ainirobot.robotos.R;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 机械臂控制模块
 * 测试所有机械臂 API：
 * 1. 移动控制：相对移动、绝对移动、重置到默认位置
 * 2. 查询：获取左右臂位置
 * 3. 监控：实时位置监听
 * 4. 停止：停止左臂、右臂、双臂
 */
public class ArmControlFragment extends Fragment {

    private SeekBar seekbarAngle;
    private TextView tvAngleValue;
    private Button btnResetAngle;
    private EditText etSpeed;
    private TextView tvStatus;  // 状态显示

    // 当前角度值（-180 到 +180）
    private int currentAngle = 0;

    // 返回按钮
    private Button btnBack;

    // 左臂控制按钮
    private Button btnLeftRelMove;
    private Button btnLeftAbsMove;
    private Button btnLeftReset;  // 重置左臂到默认位置

    // 右臂控制按钮
    private Button btnRightRelMove;
    private Button btnRightAbsMove;
    private Button btnRightReset;  // 重置右臂到默认位置

    // 双臂重置按钮
    private Button btnBothReset;  // 重置双臂到默认位置

    // 双臂控制按钮
    private Button btnDualRelMove;   // 双臂相对移动
    private Button btnDualAbsMove;   // 双臂绝对移动
    private Button btnDualAbsReset;  // 双臂重置到 0°

    // 查询按钮
    private Button btnQueryLeft;
    private Button btnQueryRight;

    // 监控按钮
    private Button btnStartMonitor;
    private Button btnStopMonitor;

    // 停止控制按钮
    private Button btnStopLeft;
    private Button btnStopRight;
    private Button btnStopDual;

    // 实时监控监听器
    private String mMonitorListenerId;
    private StatusListener mStatusListener;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // 创建自定义 DPI 的 Context 和 LayoutInflater
        Configuration config = getResources().getConfiguration();
        config.densityDpi = 320;
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());

        View root = inflater.inflate(R.layout.fragment_arm_control_layout, container, false);
        initViews(root);
        LogTools.info("ArmControlFragment created with DPI 320");
        return root;
    }

    private void initViews(View root) {
        // 返回按钮
        btnBack = root.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });

        // 角度滑块控件
        seekbarAngle = root.findViewById(R.id.seekbar_angle);
        tvAngleValue = root.findViewById(R.id.tv_angle_value);
        btnResetAngle = root.findViewById(R.id.btn_reset_angle);
        etSpeed = root.findViewById(R.id.et_speed);
        tvStatus = root.findViewById(R.id.tv_status);

        // 设置默认值
        etSpeed.setText("50");
        updateStatus("就绪");

        // 初始化角度滑块
        initAngleSeekBar();

        // 左臂控制按钮
        btnLeftRelMove = root.findViewById(R.id.btn_left_rel_move);
        btnLeftAbsMove = root.findViewById(R.id.btn_left_abs_move);
        btnLeftReset = root.findViewById(R.id.btn_left_reset);

        // 右臂控制按钮
        btnRightRelMove = root.findViewById(R.id.btn_right_rel_move);
        btnRightAbsMove = root.findViewById(R.id.btn_right_abs_move);
        btnRightReset = root.findViewById(R.id.btn_right_reset);

        // 双臂重置按钮
        btnBothReset = root.findViewById(R.id.btn_both_reset);

        // 双臂控制按钮
        btnDualRelMove = root.findViewById(R.id.btn_dual_rel_move);
        btnDualAbsMove = root.findViewById(R.id.btn_dual_abs_move);
        btnDualAbsReset = root.findViewById(R.id.btn_dual_abs_reset);

        // 查询按钮
        btnQueryLeft = root.findViewById(R.id.btn_query_left);
        btnQueryRight = root.findViewById(R.id.btn_query_right);

        // 监控按钮
        btnStartMonitor = root.findViewById(R.id.btn_start_monitor);
        btnStopMonitor = root.findViewById(R.id.btn_stop_monitor);

        // 停止控制按钮
        btnStopLeft = root.findViewById(R.id.btn_stop_left);
        btnStopRight = root.findViewById(R.id.btn_stop_right);
        btnStopDual = root.findViewById(R.id.btn_stop_dual);

        // 左臂控制事件
        btnLeftRelMove.setOnClickListener(v -> armLeftRelMove());
        btnLeftAbsMove.setOnClickListener(v -> armLeftAbsMove());
        btnLeftReset.setOnClickListener(v -> armLeftReset());

        // 右臂控制事件
        btnRightRelMove.setOnClickListener(v -> armRightRelMove());
        btnRightAbsMove.setOnClickListener(v -> armRightAbsMove());
        btnRightReset.setOnClickListener(v -> armRightReset());

        // 双臂重置事件
        btnBothReset.setOnClickListener(v -> armBothReset());

        // 双臂控制事件
        btnDualRelMove.setOnClickListener(v -> armDualRelMove());
        btnDualAbsMove.setOnClickListener(v -> armDualAbsMove());
        btnDualAbsReset.setOnClickListener(v -> armDualAbsReset());

        // 查询事件
        btnQueryLeft.setOnClickListener(v -> queryLeftArmPosition());
        btnQueryRight.setOnClickListener(v -> queryRightArmPosition());

        // 监控事件
        btnStartMonitor.setOnClickListener(v -> startMonitoring());
        btnStopMonitor.setOnClickListener(v -> stopMonitoring());

        // 停止控制事件
        btnStopLeft.setOnClickListener(v -> armLeftStop());
        btnStopRight.setOnClickListener(v -> armRightStop());
        btnStopDual.setOnClickListener(v -> armDualStop());
    }

    /**
     * 初始化角度滑块
     * 范围：-180 到 +180 度
     */
    private void initAngleSeekBar() {
        // 滑块监听器
        seekbarAngle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // progress 范围: 0-360，对应角度: -180 到 +180
                currentAngle = progress - 180;
                tvAngleValue.setText(currentAngle + "°");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 开始拖动
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 结束拖动
                LogTools.info("角度调整为: " + currentAngle + "°");
            }
        });

        // 重置按钮：重置到 0 度
        btnResetAngle.setOnClickListener(v -> {
            seekbarAngle.setProgress(180);  // 中间位置 = 0°
            Toast.makeText(getActivity(), "角度已重置为 0°", Toast.LENGTH_SHORT).show();
        });

        // 设置初始值为 0 度
        seekbarAngle.setProgress(180);  // 初始为 0°
    }

    // ==================== 移动控制 API ====================

    /**
     * 左臂相对移动（带完成回调）
     * 使用滑块选择的角度（-180 到 +180）
     */
    private void armLeftRelMove() {
        int angle = currentAngle;  // 使用滑块角度
        int speed = getSpeed();

        if (speed < 0) {
            return;
        }

        LogTools.info("左臂相对移动: angle=" + angle + "°, speed=" + speed);
        String direction = angle >= 0 ? "+" : "";
        updateStatus("左臂相对移动 " + direction + angle + "°...");

        RobotApi.getInstance().armLeftRelMove(0, angle, speed, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("左臂相对移动 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("左臂相对移动: 成功");
                    showToast("左臂相对移动 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("左臂相对移动失败 - " + message + " - " + extraData);
                    updateStatus("左臂相对移动: 失败 - " + message);
                    showToast("左臂相对移动 失败");
                }
            }
        });
    }

    /**
     * 左臂绝对移动（带完成回调）
     */
    private void armLeftAbsMove() {
        int angle = currentAngle;
        int speed = getSpeed();

        if (speed < 0) {
            return;
        }

        LogTools.info("左臂绝对移动: angle=" + angle + "°, speed=" + speed);
        updateStatus("左臂移动到 " + angle + "°...");

        RobotApi.getInstance().armLeftAbsMove(0, angle, speed, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("左臂绝对移动 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("左臂移动到 " + angle + "°: 成功");
                    showToast("左臂移动 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("左臂绝对移动失败 - " + message + " - " + extraData);
                    updateStatus("左臂移动到 " + angle + "°: 失败 - " + message);
                    showToast("左臂移动 失败");
                }
            }
        });
    }

    /**
     * 重置左臂到默认位置（垂直于地面）
     */
    private void armLeftReset() {
        LogTools.info("重置左臂到默认位置");
        updateStatus("重置左臂到垂直位置...");

        RobotApi.getInstance().resetLeftArmToDefault(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("左臂重置 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("左臂重置: 成功");
                    showToast("左臂重置 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("左臂重置失败 - " + message + " - " + extraData);
                    updateStatus("左臂重置: 失败 - " + message);
                    showToast("左臂重置 失败");
                }
            }
        });
    }

    /**
     * 右臂相对移动（带完成回调）
     * 使用滑块选择的角度（-180 到 +180）
     */
    private void armRightRelMove() {
        int angle = currentAngle;  // 使用滑块角度
        int speed = getSpeed();

        if (speed < 0) {
            return;
        }

        LogTools.info("右臂相对移动: angle=" + angle + "°, speed=" + speed);
        String direction = angle >= 0 ? "+" : "";
        updateStatus("右臂相对移动 " + direction + angle + "°...");

        RobotApi.getInstance().armRightRelMove(0, angle, speed, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("右臂相对移动 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("右臂相对移动: 成功");
                    showToast("右臂相对移动 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("右臂相对移动失败 - " + message + " - " + extraData);
                    updateStatus("右臂相对移动: 失败 - " + message);
                    showToast("右臂相对移动 失败");
                }
            }
        });
    }

    /**
     * 右臂绝对移动（带完成回调）
     */
    private void armRightAbsMove() {
        int angle = currentAngle;
        int speed = getSpeed();

        if (speed < 0) {
            return;
        }

        LogTools.info("右臂绝对移动: angle=" + angle + "°, speed=" + speed);
        updateStatus("右臂移动到 " + angle + "°...");

        RobotApi.getInstance().armRightAbsMove(0, angle, speed, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("右臂绝对移动 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("右臂移动到 " + angle + "°: 成功");
                    showToast("右臂移动 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("右臂绝对移动失败 - " + message + " - " + extraData);
                    updateStatus("右臂移动到 " + angle + "°: 失败 - " + message);
                    showToast("右臂移动 失败");
                }
            }
        });
    }

    /**
     * 重置右臂到默认位置（垂直于地面）
     */
    private void armRightReset() {
        LogTools.info("重置右臂到默认位置");
        updateStatus("重置右臂到垂直位置...");

        RobotApi.getInstance().resetRightArmToDefault(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("右臂重置 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("右臂重置: 成功");
                    showToast("右臂重置 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("右臂重置失败 - " + message + " - " + extraData);
                    updateStatus("右臂重置: 失败 - " + message);
                    showToast("右臂重置 失败");
                }
            }
        });
    }

    /**
     * 重置双臂到默认位置（垂直于地面）
     * 同时重置左右两臂，保证同步执行
     */
    private void armBothReset() {
        LogTools.info("重置双臂到默认位置");
        updateStatus("重置双臂到垂直位置...");

        RobotApi.getInstance().resetBothArmsToDefault(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("双臂重置 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("双臂重置: 成功");
                    showToast("双臂重置 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("双臂重置失败 - " + message + " - " + extraData);
                    updateStatus("双臂重置: 失败 - " + message);
                    showToast("双臂重置 失败");
                }
            }
        });
    }

    // ==================== 双臂移动 API ====================

    /**
     * 双臂相对移动（使用滑块当前角度）
     */
    private void armDualRelMove() {
        int angle = currentAngle;  // 使用滑块角度
        int speed = getSpeed();

        if (speed < 0) {
            return;
        }

        LogTools.info("双臂相对移动: angle=" + angle + "°, speed=" + speed);
        String direction = angle >= 0 ? "+" : "";
        updateStatus("双臂相对移动 " + direction + angle + "°...");

        RobotApi.getInstance().armDualRelMove(0, angle, angle, speed, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("双臂相对移动 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("双臂相对移动: 成功");
                    showToast("双臂相对移动 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("双臂相对移动失败 - " + message + " - " + extraData);
                    updateStatus("双臂相对移动: 失败 - " + message);
                    showToast("双臂相对移动 失败");
                }
            }
        });
    }

    /**
     * 双臂绝对移动（使用滑块当前角度）
     */
    private void armDualAbsMove() {
        int angle = currentAngle;  // 使用滑块角度
        int speed = getSpeed();

        if (speed < 0) {
            return;
        }

        LogTools.info("双臂绝对移动: angle=" + angle + "°, speed=" + speed);
        updateStatus("双臂移动到 " + angle + "°...");

        RobotApi.getInstance().armDualAbsMove(0, angle, angle, speed, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("双臂绝对移动 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("双臂移动到 " + angle + "°: 成功");
                    showToast("双臂移动 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("双臂绝对移动失败 - " + message + " - " + extraData);
                    updateStatus("双臂移动到 " + angle + "°: 失败 - " + message);
                    showToast("双臂移动 失败");
                }
            }
        });
    }

    /**
     * 双臂重置到 0° （不修改滑块值）
     */
    private void armDualAbsReset() {
        int speed = getSpeed();

        if (speed < 0) {
            return;
        }

        LogTools.info("双臂重置到 -30°, speed=" + speed);
        updateStatus("双臂重置到 -30°...");

        // 注意：这里固定使用 0°，不使用滑块的值
        RobotApi.getInstance().armDualAbsMove(0, -30, -30, speed, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("双臂重置到0° result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    updateStatus("双臂重置到 0°: 成功");
                    showToast("双臂重置 成功");
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("双臂重置到0°失败 - " + message + " - " + extraData);
                    updateStatus("双臂重置到 0°: 失败 - " + message);
                    showToast("双臂重置 失败");
                }
            }
        });
    }

    // ==================== 查询 API ====================

    /**
     * 查询左臂位置
     */
    private void queryLeftArmPosition() {
        LogTools.info("查询左臂位置");
        updateStatus("查询左臂位置...");

        RobotApi.getInstance().getLeftArmPosition(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("查询左臂位置 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    try {
                        JSONObject json = new JSONObject(extraData);
                        String arm = json.optString(Definition.JSON_ARM);
                        int angle = json.optInt(Definition.JSON_ARM_ANGLE);
                        int speed = json.optInt(Definition.JSON_ARM_SPEED);

                        String info = String.format("左臂位置: arm=%s, angle=%d°, speed=%d", arm, angle, speed);
                        updateStatus(info);
                        showToast(info);
                    } catch (JSONException e) {
                        LogTools.info("解析左臂位置失败: " + e.getMessage());
                        updateStatus("查询失败：解析错误");
                    }
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("查询左臂位置失败 - " + message + " - " + extraData);
                    updateStatus("查询左臂位置失败: " + message);
                }
            }
        });
    }

    /**
     * 查询右臂位置
     */
    private void queryRightArmPosition() {
        LogTools.info("查询右臂位置");
        updateStatus("查询右臂位置...");

        RobotApi.getInstance().getRightArmPosition(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                LogTools.info("查询右臂位置 result:" + result + " message:" + message + " extraData:" + extraData);
                if (result == Definition.RESULT_SUCCEED && Definition.SUCCEED.equals(message)) {
                    // ✅ 成功
                    try {
                        JSONObject json = new JSONObject(extraData);
                        String arm = json.optString(Definition.JSON_ARM);
                        int angle = json.optInt(Definition.JSON_ARM_ANGLE);
                        int speed = json.optInt(Definition.JSON_ARM_SPEED);

                        String info = String.format("右臂位置: arm=%s, angle=%d°, speed=%d", arm, angle, speed);
                        updateStatus(info);
                        showToast(info);
                    } catch (JSONException e) {
                        LogTools.info("解析右臂位置失败: " + e.getMessage());
                        updateStatus("查询失败：解析错误");
                    }
                } else {
                    // ❌ 失败（包含超时、失败、异常）
                    LogTools.info("查询右臂位置失败 - " + message + " - " + extraData);
                    updateStatus("查询右臂位置失败: " + message);
                }
            }
        });
    }

    // ==================== 实时监控 API ====================

    /**
     * 开始实时监控机械臂位置
     */
    private void startMonitoring() {
        if (mMonitorListenerId != null) {
            showToast("监控已启动");
            return;
        }

        LogTools.info("开始实时监控");
        updateStatus("启动实时监控...");

        mStatusListener = new StatusListener() {
            @Override
            public void onStatusUpdate(String type, String data) {
                try {
                    JSONObject json = new JSONObject(data);
                    String arm = json.optString(Definition.JSON_ARM);
                    int angle = json.optInt(Definition.JSON_ARM_ANGLE);
                    int speed = json.optInt(Definition.JSON_ARM_SPEED);

                    String armName;
                    if (Definition.ARM_LEFT.equals(arm)) {
                        armName = "左臂";
                    } else if (Definition.ARM_RIGHT.equals(arm)) {
                        armName = "右臂";
                    } else {
                        armName = "未知(" + arm + ")";
                    }
                    String info = String.format("%s: %d° (speed=%d)", armName, angle, speed);
                    updateStatus("[监控] " + info);
                    LogTools.info(info);
                } catch (JSONException e) {
                    LogTools.info("解析监控数据失败: " + e.getMessage());
                }
            }
        };

        mMonitorListenerId = RobotApi.getInstance().registerArmPositionListener(mStatusListener);

        if (mMonitorListenerId != null) {
            updateStatus("实时监控已启动");
            showToast("监控已启动");
            LogTools.info("监控已启动, ID=" + mMonitorListenerId);
        } else {
            updateStatus("监控启动失败");
            showToast("监控启动失败");
            mStatusListener = null;
        }
    }

    /**
     * 停止实时监控
     */
    private void stopMonitoring() {
        if (mMonitorListenerId == null) {
            showToast("监控未启动");
            return;
        }

        LogTools.info("停止实时监控");

        boolean result = RobotApi.getInstance().unregisterArmPositionListener(mStatusListener);

        if (result) {
            updateStatus("监控已停止");
            showToast("监控已停止");
            LogTools.info("监控已停止");
        } else {
            updateStatus("停止监控失败");
            showToast("停止监控失败");
        }

        mMonitorListenerId = null;
        mStatusListener = null;
    }

    // ==================== 停止控制 API ====================

    /**
     * 停止左臂运动
     */
    private void armLeftStop() {
        LogTools.info("停止左臂");
        updateStatus("停止左臂");
        RobotApi.getInstance().armLeftStop(0);
        showToast("左臂已停止");
    }

    /**
     * 停止右臂运动
     */
    private void armRightStop() {
        LogTools.info("停止右臂");
        updateStatus("停止右臂");
        RobotApi.getInstance().armRightStop(0);
        showToast("右臂已停止");
    }

    /**
     * 停止双臂运动
     */
    private void armDualStop() {
        LogTools.info("停止双臂");
        updateStatus("停止双臂");
        RobotApi.getInstance().armDualStop(0);
        showToast("双臂已停止");
    }

    // ==================== 工具方法 ====================

    /**
     * 获取速度参数
     */
    private int getSpeed() {
        String speedStr = etSpeed.getText().toString().trim();
        if (TextUtils.isEmpty(speedStr)) {
            showToast("请输入速度");
            return -1;
        }
        try {
            int speed = Integer.parseInt(speedStr);
//            if (speed < 0 || speed > 100) {
//                showToast("速度范围: 0-100");
//                return -1;
//            }
            return speed;
        } catch (NumberFormatException e) {
            showToast("速度格式错误");
            return -1;
        }
    }

    /**
     * 更新状态显示
     */
    private void updateStatus(String status) {
        if (tvStatus != null && getActivity() != null) {
            getActivity().runOnUiThread(() -> tvStatus.setText(status));
        }
    }

    /**
     * 显示 Toast
     */
    private void showToast(String message) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() ->
                Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show()
            );
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 停止监控
        if (mMonitorListenerId != null && mStatusListener != null) {
            RobotApi.getInstance().unregisterArmPositionListener(mStatusListener);
            mMonitorListenerId = null;
            mStatusListener = null;
        }
    }

    public static ArmControlFragment newInstance() {
        return new ArmControlFragment();
    }
}
