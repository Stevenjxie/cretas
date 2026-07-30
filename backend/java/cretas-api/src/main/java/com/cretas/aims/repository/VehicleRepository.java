package com.cretas.aims.repository;

import com.cretas.aims.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 车辆数据访问层
 */
@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, String> {

    /**
     * 根据工厂ID查询所有车辆（不包含已删除）
     */
    @Query("SELECT v FROM Vehicle v WHERE v.factoryId = :factoryId AND v.deletedAt IS NULL ORDER BY v.createdAt DESC")
    List<Vehicle> findByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 根据工厂ID和状态查询车辆
     */
    @Query("SELECT v FROM Vehicle v WHERE v.factoryId = :factoryId AND v.status = :status AND v.deletedAt IS NULL ORDER BY v.createdAt DESC")
    List<Vehicle> findByFactoryIdAndStatus(@Param("factoryId") String factoryId, @Param("status") Vehicle.VehicleStatus status);

    /**
     * 查询可用车辆
     */
    @Query("SELECT v FROM Vehicle v WHERE v.factoryId = :factoryId AND v.status = 'available' AND v.deletedAt IS NULL ORDER BY v.capacity DESC")
    List<Vehicle> findAvailableByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 根据车牌号查询
     */
    @Query("SELECT v FROM Vehicle v WHERE v.factoryId = :factoryId AND v.plateNumber = :plateNumber AND v.deletedAt IS NULL")
    Optional<Vehicle> findByFactoryIdAndPlateNumber(@Param("factoryId") String factoryId, @Param("plateNumber") String plateNumber);

    /**
     * 根据ID和工厂ID查询（确保租户隔离）
     */
    @Query("SELECT v FROM Vehicle v WHERE v.id = :id AND v.factoryId = :factoryId AND v.deletedAt IS NULL")
    Optional<Vehicle> findByIdAndFactoryId(@Param("id") String id, @Param("factoryId") String factoryId);
}
