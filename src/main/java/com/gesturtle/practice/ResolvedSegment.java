package com.gesturtle.practice;

import com.gesturtle.model.Segment;

import java.util.List;

public record ResolvedSegment(Segment segment, List<Integer> imageIds) {
}
