package com.palworld.vd;

/** Shared playback controls: bounded fine seeking and durable speed validation. */
final class PlaybackMath {
    static float speed(String value) {
        try {
            float result=Float.parseFloat(value);
            return Float.isNaN(result) || Float.isInfinite(result) || result<0.25f || result>4f ? 1f : result;
        } catch(Exception invalid) { return 1f; }
    }
    static long swipe(long from,long duration,float distance,float width) {
        if(duration<=0 || width<=0 || Float.isNaN(distance) || Float.isInfinite(distance)) return Math.max(0,from);
        double delta=(double)distance/width*Math.min(duration,120000L);
        return (long)Math.max(0,Math.min(Math.max(0,duration-250),from+delta));
    }
}
