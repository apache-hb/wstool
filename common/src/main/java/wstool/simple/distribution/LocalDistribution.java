package wstool.simple.distribution;

import java.util.List;

public record LocalDistribution(String name, List<String> platforms) implements AnyDistribution {

}
