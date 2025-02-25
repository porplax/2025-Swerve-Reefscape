package frc.robot.subsystems.Drivetrain;

import com.ctre.phoenix6.hardware.CANcoder;
import com.revrobotics.spark.SparkMax;
import com.revrobotics.spark.SparkLowLevel.MotorType;

import com.revrobotics.RelativeEncoder;

import edu.wpi.first.math.geometry.Rotation2d;
import frc.robot.Constants;
import frc.robot.subsystems.Drivetrain.SwerveModuleConfig.ModuleConfig;

/*
 * Modified from https://github.com/DIEHDZ/Swerve-base-Crescendo-2024/blob/master/src/main/java/frc/robot/subsystems/Drivetrain/SwerveModule.java
 */
public class SwerveModule {
    public int moduleNumber;
    private Rotation2d CANCoderAngleOffset;

    private SparkMax rotationMotor; // Rotation Neo Motor Controller
    private SparkMax driveMotor; // Drive Neo Motorf Controller

    // private RelativeEncoder rotationEncoder; // Encoder from the NEO motor
    // private RelativeEncoder driveEncoder; // Encoder from the NEO motor

    private CANcoder absoluteEncoder; // CANcoder located above the swerve module

    public SwerveModule(int moduleNumber, ModuleConfig moduleConfig) {
        this.moduleNumber = moduleNumber;
        this.CANCoderAngleOffset = moduleConfig.angleOffset;

        // CANCoder config
        this.absoluteEncoder = new CANcoder(moduleConfig.canCoderID);
        Swerve.configureCANcoder(absoluteEncoder, moduleConfig.canCoderInvert);

        // rotation motor config
        this.rotationMotor = new SparkMax(moduleConfig.rotationMotorID, MotorType.kBrushless);
        // this.rotationEncoder = rotationMotor.getEncoder();
        // configRotationMotor(moduleConfig.rotationInvert);

        // drive Motor Config
        this.driveMotor = new SparkMax(moduleConfig.driveMotorID, MotorType.kBrushless);
        // this.driveEncoder = driveMotor.getEncoder();
        // configDriveMotor(moduleConfig.driveInvert);
    }

    // Directly controls wheel rotation angle, doesnt queue to update
    public boolean setAngle(double targetAngle, boolean driveMode) {
        targetAngle = targetAngle + 180; // convery from -180,180 to 0,360
        double currentAngle = getCanCoderDegrees(); // Adjust current angle by the offset
        currentAngle = ((currentAngle % 360 + 360) % 360);
        targetAngle = ((targetAngle) % 360 + 360) % 360; // Adjust target angle by the offset
        double deltaAngle = ((targetAngle - currentAngle + 540) % 360) - 180;
        double speed = deltaAngle / 180.0; // Scales the speed to [-1, 1] as deltaAngle ranges from -180 to 180
        speed = Math.max(-Constants.Swerve.maxWheelRotateSpeed, Math.min(Constants.Swerve.maxWheelRotateSpeed, speed));
        setRotationSpeed(speed);
        if (driveMode) {
            if (speed > 0.03) {
                return false;
            } else {
                return true;
            }
        }
        return false;
    }
    
    // Directly controls wheel rotation angle, doesnt queue to update
    public void setRotationSpeed(double speed) {
        rotationMotor.set(speed);
    }

    // Directly controls wheel drive speed, doesnt queue to update
    public void setDriveSpeed(double speed) {
        driveMotor.set(speed);
    }

    // Get the absolute rotation position of the wheel, and subtract the offset on certain CANCoders
    public double getCanCoderDegrees() {
        return ((absoluteEncoder.getAbsolutePosition(true).getValueAsDouble()*360 - CANCoderAngleOffset.getDegrees())+360)%360;
    }
}