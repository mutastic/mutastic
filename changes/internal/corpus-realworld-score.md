- **The corpus expects a score of spring-boot-realworld.** It runs on Gradle 7.4 since A6 was
  fixed (#222, #223), and its Spring 5 proxies are no longer defined twice across runs (#246,
  #247), so its tests no longer fail under mutastic where they pass in the build.
