// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems.drive;

import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.CANcoderConfiguration;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.configs.MagnetSensorConfigs;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.NeutralModeValue;

import com.revrobotics.spark.SparkFlex;
import com.revrobotics.spark.SparkLowLevel.MotorType;
import com.revrobotics.spark.SparkAbsoluteEncoder;
import com.revrobotics.spark.SparkBase.ResetMode;
import com.revrobotics.spark.config.SparkFlexConfig;
import com.revrobotics.spark.config.SparkBaseConfig.IdleMode;
import com.revrobotics.spark.SparkBase.PersistMode;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.math.util.Units;
import frc.robot.Constants.DriveConstants;
import frc.robot.Constants.ModuleConstants;
import frc.robot.utils.TalonUtils; // Adjust the package name as necessary

public class SwerveModule {

  private final int m_id;

  private final SparkFlex m_driveMotor;
  private final SparkFlex m_turningMotor;

  private final CANcoder m_turningEncoder;
  private final PIDController m_drivePIDController = new PIDController(DriveConstants.kPDrive, 0, 0);
  private final PIDController m_turnPIDController = new PIDController(ModuleConstants.kPModuleTurningNoController, 0, 0);
  

  private final SimpleMotorFeedforward m_driveFeedforward = new SimpleMotorFeedforward(DriveConstants.ksVoltsDrive,
      DriveConstants.kvVoltsDrive, DriveConstants.kaVoltsDrive);


  /**
   * Constructs a SwerveModule.
   *
   * @param driveMotorChannel      The channel of the drive motor.
   * @param turningMotorChannel    The channel of the turning motor.
   * @param driveEncoderChannels   The channels of the drive encoder.
   * @param turningEncoderChannels The channels of the turning encoder.
   * @param driveEncoderReversed   Whether the drive encoder is reversed.
   * @param turningEncoderReversed Whether the turning encoder is reversed.
   */
  public SwerveModule(
      int driveMotorChannel,
      int turningMotorChannel,
      int turningEncoderChannel,
      String canName,
      boolean invertTurning,
      boolean invertDrive,
      double turningEncoderOffset) {

    m_id = driveMotorChannel;

    // Configure Driving motor & motor 
    
    m_driveMotor = new SparkFlex(driveMotorChannel, MotorType.kBrushless);
    SparkFlexConfig drive_motor_config = new SparkFlexConfig();
    drive_motor_config
        .smartCurrentLimit(40)
        .idleMode(IdleMode.kBrake);
    // Persist parameters to retain configuration in the event of a power cycle
    m_driveMotor.configure(drive_motor_config, ResetMode.kResetSafeParameters, PersistMode.kPersistParameters);

    // Configure Turning motor & motor controller

    m_turningMotor = new SparkFlex(turningMotorChannel, MotorType.kBrushless);
    SparkFlexConfig turning_motor_config = new SparkFlexConfig();
    turning_motor_config
        .smartCurrentLimit(40)
        .idleMode(IdleMode.kBrake);
    // Persist parameters to retain configuration in the event of a power cycle
    m_turningMotor.configure(turning_motor_config, ResetMode.kResetSafeParameters, PersistMode.kPersistParameters);

    m_turningEncoder = new CANcoder(turningEncoderChannel, canName);
    var turning_config = new CANcoderConfiguration();
    turning_config.MagnetSensor.MagnetOffset = turningEncoderOffset;
    turning_config.MagnetSensor.AbsoluteSensorDiscontinuityPoint = 1; //set this to 1 to get Unsigned_0To1

    if(!TalonUtils.ApplyCanCoderConfig(m_turningEncoder, turning_config)){
      System.out.println("Could not apply configs to the turning encoder");
    }
   
    // Limit the PID Controller's input range between -pi and pi and set the input
    // to be continuous.
    m_turnPIDController.enableContinuousInput(-Math.PI, Math.PI);
  }

  public SparkFlex getTurningMotor(){
    return m_turningMotor;
  }
  public SparkFlex getDriveMotor(){
    return m_driveMotor;
  }

  private double getTurnEncoderPositionInRadians() {
    m_turningEncoder.getAbsolutePosition().refresh();
    double retVal = Units.rotationsToRadians(m_turningEncoder.getAbsolutePosition().getValueAsDouble());
    return retVal;
  }

  private double getDriveEncoderVelocityMetersPerSecond() {
    return m_driveMotor.getAbsoluteEncoder().getVelocity() * (2 * Math.PI * ModuleConstants.kWheelRadius)
        / ModuleConstants.kDriveGearRatio;
  }

  private double getDriveEncoderPositionMeters() {
    return m_driveMotor.getAbsoluteEncoder().getPosition() * (2 * Math.PI * ModuleConstants.kWheelRadius)
        / ModuleConstants.kDriveGearRatio;
  }

  /**
   * Returns the current state of the module.
   *
   * @return The current state of the module.
   */
  public SwerveModuleState getState() {
    return new SwerveModuleState(
        getDriveEncoderVelocityMetersPerSecond(), new Rotation2d(getTurnEncoderPositionInRadians()));
  }

  /**
   * Returns the current position of the module.
   *
   * @return The current position of the module.
   */
  public SwerveModulePosition getPosition() {
    return new SwerveModulePosition(
        getDriveEncoderPositionMeters(), new Rotation2d(getTurnEncoderPositionInRadians()));
  }

  /**
   * Sets the desired state for the module.
   *
   * @param desiredState Desired state with speed and angle.
   */
  public void setDesiredState(SwerveModuleState desiredState) {
    // Optimize the reference state to avoid spinning further than 90 degrees
    desiredState.optimize(
        new Rotation2d(getTurnEncoderPositionInRadians()));

    // Calculate the drive output from the drive PID controller.
    final double driveOutput = m_drivePIDController.calculate(getDriveEncoderVelocityMetersPerSecond(), desiredState.speedMetersPerSecond);

    final double driveFeedforward = m_driveFeedforward.calculate(desiredState.speedMetersPerSecond);

    // Calculate the turning motor output from the turning PID controller.
    double turnOutput = m_turnPIDController.calculate(getTurnEncoderPositionInRadians(),
    desiredState.angle.getRadians());

    // //SmartDashboard.putNumber("velcity set " +m_id,getDriveEncoderVelocity() );

    //// SmartDashboard.putNumber("velcity req " +m_id,state.speedMetersPerSecond );

    //// SmartDashboard.putNumber("turn set "
    //// +m_id,getTurnEncoderPositionInRadians() );

    //// SmartDashboard.putNumber("turn req " +m_id,state.angle.getRadians());

    //// SmartDashboard.putNumber("drive vo " +m_id,driveOutput );
    //// SmartDashboard.putNumber("drive vf " +m_id,driveFeedforward);


    m_driveMotor.setVoltage(driveOutput + driveFeedforward);
  
    m_turningMotor.setVoltage(turnOutput);
  }

  public void lockTurningAtZero(){
     double turnOutput = m_turnPIDController.calculate(getTurnEncoderPositionInRadians(),
       0);
       m_turningMotor.setVoltage(turnOutput);
  }

  public void forceStop() {
    m_driveMotor.setVoltage(0);
    m_turningMotor.setVoltage(0);
  }

  /** Zeroes all the SwerveModule encoders. */
  public void resetEncoders() {
    //m_driveMotor.getAbsoluteEncoder().setPosition(0);
  }
}
