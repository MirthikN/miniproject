CREATE DATABASE IF NOT EXISTS water_db;
USE water_db;

CREATE TABLE IF NOT EXISTS water_source (
  source_id VARCHAR(30) PRIMARY KEY, source_name VARCHAR(100) NOT NULL,
  total_capacity INT NOT NULL, remaining_water INT NOT NULL, ord INT NOT NULL);

CREATE TABLE IF NOT EXISTS distribution_area (
  area_id VARCHAR(30) PRIMARY KEY, area_name VARCHAR(100) NOT NULL, area_type VARCHAR(15) NOT NULL,
  population INT NOT NULL, daily_demand INT NOT NULL, last_supply_day INT NOT NULL,
  last_delivered INT NOT NULL, ord INT NOT NULL);

CREATE TABLE IF NOT EXISTS pipeline (
  pipeline_id VARCHAR(30) PRIMARY KEY, from_id VARCHAR(30) NOT NULL, to_id VARCHAR(30) NOT NULL,
  status VARCHAR(10) NOT NULL, last_note VARCHAR(255), ord INT NOT NULL);

CREATE TABLE IF NOT EXISTS water_request (
  request_id VARCHAR(30) PRIMARY KEY, area_id VARCHAR(30) NOT NULL, required_water INT NOT NULL,
  emergency BOOLEAN NOT NULL, seq_no INT NOT NULL, days_since_supply INT NOT NULL,
  priority VARCHAR(10) NOT NULL, allocated INT NOT NULL, unmet INT NOT NULL, status VARCHAR(25) NOT NULL);

CREATE TABLE IF NOT EXISTS maintenance_request (
  maint_id VARCHAR(30) PRIMARY KEY, pipeline_id VARCHAR(30) NOT NULL, issue VARCHAR(255),
  status VARCHAR(12) NOT NULL, ord INT NOT NULL);

CREATE TABLE IF NOT EXISTS allocation_history (
  id INT AUTO_INCREMENT PRIMARY KEY, message VARCHAR(500) NOT NULL);

CREATE TABLE IF NOT EXISTS app_state (
  id INT PRIMARY KEY, current_day INT NOT NULL, supply_interval INT NOT NULL,
  seq_counter INT NOT NULL, maint_counter INT NOT NULL);
