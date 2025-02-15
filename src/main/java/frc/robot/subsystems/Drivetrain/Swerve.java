package frc.robot.subsystems.Drivetrain;

import static edu.wpi.first.units.Units.Meter;
import static edu.wpi.first.units.Units.MetersPerSecond;

import java.io.File;
import java.util.Optional;
import java.util.function.Supplier;

import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.commands.PathfindingCommand;
import com.pathplanner.lib.config.PIDConstants;
import com.pathplanner.lib.config.RobotConfig;
import com.pathplanner.lib.controllers.PPHolonomicDriveController;
import com.pathplanner.lib.path.PathConstraints;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import frc.robot.subsystems.Vision.Vision;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Filesystem;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants;
import frc.robot.subsystems.Vision.Vision.Cameras;
import swervelib.SwerveDrive;
import swervelib.math.SwerveMath;

import org.photonvision.targeting.PhotonPipelineResult;
import swervelib.parser.SwerveParser;
import swervelib.telemetry.SwerveDriveTelemetry;
import swervelib.telemetry.SwerveDriveTelemetry.TelemetryVerbosity;

/**
 * Manages the robot's swerve drive system, providing control over movement,
 * autonomous path following,
 * and odometry tracking. This subsystem handles both autonomous and
 * teleoperated drive control,
 * integrating with PathPlanner for advanced autonomous capabilities.
 * 
 * <p>
 * Features include:
 * <ul>
 * <li>Field-oriented drive control
 * <li>Autonomous path following and path finding
 * <li>Odometry tracking and pose estimation
 * <li>Wheel locking for stability
 * </ul>
 * 
 * <p>
 * Uses YAGSL (Yet Another Generic Swerve Library) for underlying swerve drive
 * implementation
 * and PathPlanner for autonomous navigation.
 */

// TAKEN FROM
// https://github.com/4533-phoenix/frc-2025-robot/blob/main/src/main/java/frc/robot/subsystems/Swerve.java
public class Swerve extends SubsystemBase {
    private static Swerve instance;
    private final SwerveDrive swerveDrive;
    /**
     * Enable vision odometry updates while driving.
     */
    private final boolean visionDriveTest = true;
    /**
     * PhotonVision class to keep an accurate odometry.
     */
    private Vision vision;

    /**
     * Returns the singleton instance of the Swerve subsystem.
     * Creates a new instance if one does not exist.
     * 
     * @return the Swerve subsystem instance
     */
    public static Swerve getInstance() {
        if (instance == null) {
            instance = new Swerve();
        }
        return instance;
    }

    /**
     * Creates a new Swerve subsystem that manages drive control, path following,
     * and odometry.
     * Initializes the swerve drive with high telemetry verbosity and configures
     * various drive parameters.
     * 
     * @throws RuntimeException if swerve drive creation fails
     */
    public Swerve() {
        SwerveDriveTelemetry.verbosity = TelemetryVerbosity.HIGH;
        try {
            swerveDrive = new SwerveParser(new File(Filesystem.getDeployDirectory(), "swerve"))
                    .createSwerveDrive(
                            Constants.MAX_SPEED.in(MetersPerSecond),
                            new Pose2d(new Translation2d(Meter.of(8.774), Meter.of(4.026)),
                                    Rotation2d.fromDegrees(0)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create swerve drive", e);
        }

        swerveDrive.setHeadingCorrection(false);
        swerveDrive.setAngularVelocityCompensation(true, true, 0.1);
        swerveDrive.setCosineCompensator(!SwerveDriveTelemetry.isSimulation);
        swerveDrive.setModuleEncoderAutoSynchronize(true, 1);
        swerveDrive.pushOffsetsToEncoders();

        if (visionDriveTest) {
            setupPhotonVision();
            // Stop the odometry thread if we are using vision that way we can synchronize
            // updates better.
            swerveDrive.stopOdometryThread();
        }
        setupPathPlanner();
    }

    /**
     * Configures PathPlanner for autonomous path following.
     * Sets up the necessary callbacks and controllers for autonomous navigation,
     * including pose estimation, odometry reset, and velocity control.
     * Also initializes path finding warm-up for better initial performance.
     */
    public void setupPathPlanner() {
        RobotConfig config;
        try {
            config = RobotConfig.fromGUISettings();

            final boolean enableFeedForward = true;

            AutoBuilder.configure(
                    this::getPose,
                    this::resetOdometry,
                    this::getRobotVelocity,
                    (speedsRobotRelative, moduleFeedForwards) -> {
                        if (enableFeedForward) {
                            swerveDrive.drive(
                                    speedsRobotRelative,
                                    swerveDrive.kinematics.toSwerveModuleStates(speedsRobotRelative),
                                    moduleFeedForwards.linearForces());
                        } else {
                            swerveDrive.setChassisSpeeds(speedsRobotRelative);
                        }
                    },
                    new PPHolonomicDriveController(
                            new PIDConstants(5.0, 0.0, 0.0),
                            new PIDConstants(5.0, 0.0, 0.0)),
                    config,
                    () -> {
                        var alliance = DriverStation.getAlliance();
                        if (alliance.isPresent()) {
                            return alliance.get() == DriverStation.Alliance.Red;
                        }
                        return false;
                    },
                    this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        PathfindingCommand.warmupCommand().schedule();
    }

    /**
     * Aim the robot at the target returned by PhotonVision.
     *
     * @return A {@link Command} which will run the alignment.
     */
    public Command aimAtTarget(Cameras camera) {
        return run(() -> {
            Optional<PhotonPipelineResult> resultO = camera.getBestResult();
            if (resultO.isPresent()) {
                var result = resultO.get();
                if (result.hasTargets()) {
                    System.out.println("HAS TARGETS: "+result.targets.size());
                    System.out.println("TARGETS: "+result.getTargets().toString());
                    
                    drive(getTargetSpeeds(0,
                            0,
                            Rotation2d.fromDegrees(result.getBestTarget().getYaw()))); // Not sure if this will work, more math may be required.
                }
            }
        });
    }

    /**
     * Setup the photon vision class.
     */
    public void setupPhotonVision() {
        System.out.println("Setting Up Photon Vision");

        vision = new Vision(swerveDrive::getPose, swerveDrive.field);
    }

    @Override
    public void periodic() {
        // When vision is enabled we must manually update odometry in SwerveDrive
        if (visionDriveTest) {
            swerveDrive.updateOdometry();
            vision.updatePoseEstimation(swerveDrive);
        }
    }

    /**
     * Creates a command to drive the robot in field-oriented mode.
     * Takes into account the robot's current heading to maintain consistent
     * field-relative movement.
     * 
     * @param velocity a supplier that provides the desired chassis speeds in
     *                 field-oriented coordinates
     * @return a command that continuously updates drive output based on supplied
     *         velocities
     * @see ChassisSpeeds
     */
    public Command driveFieldOriented(Supplier<ChassisSpeeds> velocity) {
        return run(() -> swerveDrive.driveFieldOriented(velocity.get()));
    }

    /**
     * Locks the swerve modules in an X pattern to prevent the robot from moving.
     * Useful for maintaining position or preparing for disable.
     */
    public void lockWheels() {
        swerveDrive.lockPose();
    }

    /**
     * Resets the robot's odometry to the center of the field (8.774m, 4.026m, 0°).
     * This is typically used at the start of autonomous routines.
     */
    public void resetOdometry() {
        swerveDrive.zeroGyro();
        swerveDrive.resetOdometry(new Pose2d(new Translation2d(Meter.of(8.774), Meter.of(4.026)),Rotation2d.fromDegrees(0)));
    }

    /**
     * Retrieves the current estimated pose of the robot on the field.
     * 
     * @return the current Pose2d representing the robot's position and rotation
     */
    public Pose2d getPose() {
        return swerveDrive.getPose();
    }

    /**
     * Resets the robot's odometry to a specific pose.
     * 
     * @param pose the Pose2d to set as the robot's current position and rotation
     */
    public void resetOdometry(Pose2d pose) {
        swerveDrive.resetOdometry(pose);
    }

    /**
     * Gets the current velocity of the robot.
     * 
     * @return the ChassisSpeeds representing the robot's current velocity
     */
    public ChassisSpeeds getRobotVelocity() {
        return swerveDrive.getRobotVelocity();
    }

    /**
     * Gets the underlying SwerveDrive object.
     * 
     * @return the SwerveDrive instance used by this subsystem
     */
    public SwerveDrive getSwerveDrive() {
        return swerveDrive;
    }

    /**
     * Creates a command to autonomously drive the robot to a specific pose using
     * PathPlanner.
     * 
     * @param pose the target Pose2d to drive to
     * @return a Command that will drive the robot to the specified pose
     */
    public Command driveToPose(Pose2d pose) {
        PathConstraints constraints = new PathConstraints(
                swerveDrive.getMaximumChassisVelocity(), 4.0,
                swerveDrive.getMaximumChassisAngularVelocity(), Units.degreesToRadians(720));

        return AutoBuilder.pathfindToPose(
                pose,
                constraints,
                edu.wpi.first.units.Units.MetersPerSecond.of(0));
    }

    /**
     * Get the chassis speeds based on controller input of 2 joysticks. One for
     * speeds in which direction. The other for
     * the angle of the robot.
     *
     * @param xInput   X joystick input for the robot to move in the X direction.
     * @param yInput   Y joystick input for the robot to move in the Y direction.
     * @param headingX X joystick which controls the angle of the robot.
     * @param headingY Y joystick which controls the angle of the robot.
     * @return {@link ChassisSpeeds} which can be sent to the Swerve Drive.
     */
    public ChassisSpeeds getTargetSpeeds(double xInput, double yInput, double headingX, double headingY) {
        Translation2d scaledInputs = SwerveMath.cubeTranslation(new Translation2d(xInput, yInput));
        return swerveDrive.swerveController.getTargetSpeeds(scaledInputs.getX(),
                scaledInputs.getY(),
                headingX,
                headingY,
                getHeading().getRadians(),
                Constants.MAX_SPEED2);
    }

    /**
     * Get the chassis speeds based on controller input of 1 joystick and one angle.
     * Control the robot at an offset of
     * 90deg.
     *
     * @param xInput X joystick input for the robot to move in the X direction.
     * @param yInput Y joystick input for the robot to move in the Y direction.
     * @param angle  The angle in as a {@link Rotation2d}.
     * @return {@link ChassisSpeeds} which can be sent to the Swerve Drive.
     */
    public ChassisSpeeds getTargetSpeeds(double xInput, double yInput, Rotation2d angle) {
        Translation2d scaledInputs = SwerveMath.cubeTranslation(new Translation2d(xInput, yInput));

        return swerveDrive.swerveController.getTargetSpeeds(scaledInputs.getX(),
                scaledInputs.getY(),
                angle.getRadians(),
                getHeading().getRadians(),
                Constants.MAX_SPEED2);
    }

    /**
     * Gets the current yaw angle of the robot, as reported by the swerve pose
     * estimator in the underlying drivebase.
     * Note, this is not the raw gyro reading, this may be corrected from calls to
     * resetOdometry().
     *
     * @return The yaw angle
     */
    public Rotation2d getHeading() {
        return getPose().getRotation();
    }

    /**
   * The primary method for controlling the drivebase.  Takes a {@link Translation2d} and a rotation rate, and
   * calculates and commands module states accordingly.  Can use either open-loop or closed-loop velocity control for
   * the wheel velocities.  Also has field- and robot-relative modes, which affect how the translation vector is used.
   *
   * @param translation   {@link Translation2d} that is the commanded linear velocity of the robot, in meters per
   *                      second. In robot-relative mode, positive x is torwards the bow (front) and positive y is
   *                      torwards port (left).  In field-relative mode, positive x is away from the alliance wall
   *                      (field North) and positive y is torwards the left wall when looking through the driver station
   *                      glass (field West).
   * @param rotation      Robot angular rate, in radians per second. CCW positive.  Unaffected by field/robot
   *                      relativity.
   * @param fieldRelative Drive mode.  True for field-relative, false for robot-relative.
   */
  public void drive(Translation2d translation, double rotation, boolean fieldRelative)
  {
    swerveDrive.drive(translation,
                      rotation,
                      fieldRelative,
                      false); // Open loop is disabled since it shouldn't be used most of the time.
  }

  /**
   * Drive according to the chassis robot oriented velocity.
   *
   * @param velocity Robot oriented {@link ChassisSpeeds}
   */
  public void drive(ChassisSpeeds velocity)
  {
    swerveDrive.drive(velocity);
  }
}