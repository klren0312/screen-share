# ADR-0001: Mobile Screen Sharing Platform

## Context

This ADR defines the overall architecture and requirements for the mobile screen sharing platform. It establishes the core domain model and architectural decisions.

## Scope

The platform enables real-time screen sharing between an Android device and a web client, with synchronized 3D visualization of the shared screen and real-time transmission of accelerometer data for posture tracking.

## Objectives

1. Enable real-time screen sharing via WebRTC
2. Transmit accelerometer data (orientation, movement) in real time
3. Render 3D models synchronized with phone pose
4. Support Android as the source device and Next.js as the web client

## Key Design Decisions

### 1. Technology Stack

- **Frontend**: Next.js (React) with Three.js for 3D rendering
- **Mobile**: Native Android (Kotlin/Java) with CameraX and WebRTC
- **Communication**: WebRTC for low-latency media transfer
- **State Management**: React context + Zustand for session state

### 2. Data Flow

1. Android captures screen via CameraX and sensor data
2. Sensor data (accelerometer, gyroscope) is fused and transmitted via WebRTC
3. Web client receives media stream and renders 3D view
4. 3D view synchronizes with phone pose based on sensor data

### 3. Architecture Layers

- **Presentation Layer**: Next.js UI (WebRTC controls, 3D viewer)
- **Application Layer**: Session manager, data processors
- **Media Layer**: WebRTC peer connections, stream encoding
- **Device Layer**: Android camera/sensor abstraction

## Risks

- **Latency**: WebRTC latency may affect real-time responsiveness
- **Sensor Accuracy**: Accelerometer drift over time
- **Cross-platform consistency**: Ensuring 3D view matches phone orientation
- **Security**: Secure WebRTC connections, data encryption

## Consequences of Decision

Choosing WebRTC provides lowest latency but requires careful NAT traversal and signaling. Choosing Next.js enables easy deployment and SEO-friendly URLs. Using Three.js provides powerful 3D capabilities but adds bundle size.

## Related Documents

- CONTEXT.md - Domain model and terminology
- docs/adr/0002-accelerometer-data-sync.md - Detailed sensor data strategy
- docs/adr/0003-threejs-integration.md - 3D rendering approach

## Approval

- Product Owner: [TBD]
- Engineering Lead: [TBD]
- Date: 2026-09-09
