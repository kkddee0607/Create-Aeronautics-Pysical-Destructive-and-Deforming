package com.physicaldestroydeform.collision;

import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

/**
 * Cheap optional proxy direction from ONE Sable callback on a verified world full-cube.
 * Sable's Rust hooks supply the hit block's own coordinates and its local impact
 * position; for STATIC_WORLD only, that is the ordinary world block frame.
 * This is NOT reciprocal point matching, nor an exact contact/normal source.
 */
public final class ImpactFaceEstimate {
    private ImpactFaceEstimate() {}

    // Reject uncertain edges/corners. Face estimates are allowed only for one
    // unambiguous, near-planar face; never fabricate a face from block centers.
    private static final double FACE_MARGIN = 0.05;
    public enum Reason { VERIFIED_WORLD_CUBE_FACE, NOT_VERIFIED_WORLD_CUBE,
        CONTACT_MISSING, CONTACT_OUTSIDE_CUBE, FACE_NOT_UNIQUE }
    public record Direction(Vec normalBtoA, Reason reason) {
        public boolean verified() { return reason == Reason.VERIFIED_WORLD_CUBE_FACE; }
    }
    public static Direction fromCallback(Evidence e, boolean hitIsVerifiedFullWorldCube) {
        if(e == null || e.hit() == null || e.hit().space() != Space.STATIC_WORLD
                || !hitIsVerifiedFullWorldCube)
            return new Direction(null, Reason.NOT_VERIFIED_WORLD_CUBE);
        Vec p=e.contactRaw();
        if(p == null || !p.finite())return new Direction(null, Reason.CONTACT_MISSING);
        double[] c={p.x()-e.hit().x(),p.y()-e.hit().y(),p.z()-e.hit().z()};
        for(double x:c)if(x < -FACE_MARGIN || x > 1.0+FACE_MARGIN)
            return new Direction(null, Reason.CONTACT_OUTSIDE_CUBE);
        int face=-1;
        int side=0;
        for(int i=0;i<3;i++) {
            if(Math.abs(c[i]) <= FACE_MARGIN) {
                if(face!=-1)return new Direction(null, Reason.FACE_NOT_UNIQUE);
                face=i;side=-1;
            }
            if(Math.abs(c[i]-1.0) <= FACE_MARGIN) {
                if(face!=-1)return new Direction(null, Reason.FACE_NOT_UNIQUE);
                face=i;side=1;
            }
        }
        if(face<0)return new Direction(null, Reason.FACE_NOT_UNIQUE);
        // Collision face outward from A (the world cube) points toward B.
        // ImpactMath's n_BtoA points opposite to that outward face.
        return new Direction(switch(face) {
            case 0 -> new Vec(-side,0,0);
            case 1 -> new Vec(0,-side,0);
            default -> new Vec(0,0,-side);
        },Reason.VERIFIED_WORLD_CUBE_FACE);
    }
}
