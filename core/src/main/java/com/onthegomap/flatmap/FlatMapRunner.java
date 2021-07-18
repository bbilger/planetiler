package com.onthegomap.flatmap;

import com.onthegomap.flatmap.collections.FeatureGroup;
import com.onthegomap.flatmap.collections.FeatureSort;
import com.onthegomap.flatmap.collections.LongLongMap;
import com.onthegomap.flatmap.monitoring.Stats;
import com.onthegomap.flatmap.monitoring.Timers;
import com.onthegomap.flatmap.read.NaturalEarthReader;
import com.onthegomap.flatmap.read.OpenStreetMapReader;
import com.onthegomap.flatmap.read.OsmInputFile;
import com.onthegomap.flatmap.read.ShapefileReader;
import com.onthegomap.flatmap.worker.Worker;
import com.onthegomap.flatmap.write.MbtilesWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlatMapRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(FlatMapRunner.class);

  private final Map<String, Worker.RunnableThatThrows> stages = new LinkedHashMap<>();
  private final Map<String, List<String>> subStages = new HashMap<>();
  private final Map<String, String> stageDescriptions = new HashMap<>();

  private final Timers.Finishable overallTimer;
  private final Arguments arguments;
  private Stats stats;
  private Profile profile = null;
  private CommonParams config;
  private LongLongMap nodeLocations;
  private FeatureSort featureDb;
  private FeatureGroup featureMap;
  private OsmInputFile osmInputFile;
  private Path tmpDir;
  private Path output;
  private boolean overwrite = false;
  private boolean ran = false;

  private FlatMapRunner(Arguments arguments) {
    this.arguments = arguments;
    stats = arguments.getStats();
    overallTimer = stats.startTimer("overall");
    tmpDir = arguments.file("tmpdir", "temp directory", Path.of("data", "tmp"));
  }

  public static FlatMapRunner create() {
    return new FlatMapRunner(Arguments.fromJvmProperties());
  }

  public FlatMapRunner addOsmSource(String name, Path defaultPath) {
    Path path = arguments.inputFile(name, "OSM input file", defaultPath);
    var thisInputFile = new OsmInputFile(path);
    osmInputFile = thisInputFile;
    subStages.put(name, List.of(name + "_pass1", name + "_pass2"));
    stageDescriptions
      .put(name + "_pass1", "Pre-process OpenStreetMap input (store node locations then relation members)");
    stageDescriptions.put(name + "_pass2", "Process OpenStreetMap nodes, ways, then relations");
    return addStage(name, ifSourceUsed(name, () -> {
      try (var osmReader = new OpenStreetMapReader(name, thisInputFile, nodeLocations,
        profile, stats)) {
        osmReader.pass1(config);
        osmReader.pass2(featureMap, config);
      }
    }));
  }

  public FlatMapRunner addShapefileSource(String name, Path defaultPath) {
    Path path = arguments.inputFile(name, name + " shapefile", defaultPath);
    return addStage(name, "Process features in " + path,
      ifSourceUsed(name, () -> ShapefileReader.process(name, path, featureMap, config, profile, stats)));
  }

  private Worker.RunnableThatThrows ifSourceUsed(String name, Worker.RunnableThatThrows task) {
    return () -> {
      if (profile.caresAboutSource(name)) {
        task.run();
      } else {
        LOGGER.info("[" + name + "] skipping since profile does not use it");
      }
    };
  }

  public FlatMapRunner addShapefileSource(String projection, String name, Path defaultPath) {
    Path path = arguments.inputFile(name, name + " shapefile", defaultPath);
    return addStage(name, "Process features in " + path,
      ifSourceUsed(name, () -> ShapefileReader.process(projection, name, path, featureMap, config, profile, stats)));
  }

  public FlatMapRunner addNaturalEarthSource(String name, Path defaultPath) {
    Path path = arguments.inputFile(name, name + " sqlite db", defaultPath);
    return addStage(name, "Process features in " + path, ifSourceUsed(name, () -> NaturalEarthReader
      .process(name, path, tmpDir.resolve("natearth.sqlite"), featureMap, config, profile, stats)));
  }

  public FlatMapRunner addStage(String name, String description, Worker.RunnableThatThrows task) {
    if (stages.containsKey(name)) {
      throw new IllegalArgumentException("Duplicate stage name: " + name);
    }
    stageDescriptions.put(name, description);
    stages.put(name, task);
    return this;
  }

  private FlatMapRunner addStage(String name, Worker.RunnableThatThrows task) {
    if (stages.containsKey(name)) {
      throw new IllegalArgumentException("Duplicate stage name: " + name);
    }
    stages.put(name, task);
    return this;
  }

  public FlatMapRunner setProfile(Profile profile) {
    this.profile = profile;
    return this;
  }

  public FlatMapRunner setOutput(String argument, Path mbtiles) {
    this.output = arguments.file(argument, "mbtiles output file", mbtiles);
    return this;
  }

  public FlatMapRunner overwriteOutput(String argument, Path mbtiles) {
    this.overwrite = true;
    return setOutput(argument, mbtiles);
  }

  public void run() throws Exception {
    if (profile == null) {
      throw new IllegalStateException("No profile specified");
    }
    if (output == null) {
      throw new IllegalStateException("No output specified");
    }
    if (stages.isEmpty()) {
      throw new IllegalStateException("No sources specified");
    }
    if (ran) {
      throw new IllegalStateException("Can only run once");
    }
    ran = true;
    config = CommonParams.from(arguments, osmInputFile);

    if (overwrite || config.forceOverwrite()) {
      FileUtils.deleteFile(output);
    } else if (Files.exists(output)) {
      throw new IllegalArgumentException(output + " already exists, use force to overwrite.");
    }

    LOGGER.info(
      "Building " + profile.getClass().getSimpleName() + " profile into " + output + " in these phases:");

    for (String stage : stages.keySet()) {
      for (String substage : subStages.getOrDefault(stage, List.of(stage))) {
        LOGGER.info("  [" + substage + "] " + stageDescriptions.getOrDefault(substage, ""));
      }
    }
    LOGGER.info("  [sort] Sort rendered features by tile ID");
    LOGGER.info("  [mbtiles] Encode each tile and write to " + output);

    Files.createDirectories(tmpDir);
    Path nodeDbPath = tmpDir.resolve("node.db");
    nodeLocations = LongLongMap.newFileBackedSortedTable(nodeDbPath);
    Path featureDbPath = tmpDir.resolve("feature.db");
    featureDb = FeatureSort
      .newExternalMergeSort(tmpDir.resolve("feature.db"), config.threads(), config.gzipTempStorage(), stats);
    featureMap = new FeatureGroup(featureDb, profile, stats);
    stats.monitorFile("nodes", nodeDbPath);
    stats.monitorFile("features", featureDbPath);
    stats.monitorFile("mbtiles", output);

    for (Worker.RunnableThatThrows stage : stages.values()) {
      stage.run();
    }

    LOGGER.info("Deleting node.db to make room for mbtiles");
    profile.release();
    Files.delete(nodeDbPath);

    featureDb.sort();

    MbtilesWriter.writeOutput(featureMap, output, profile, config, stats);

    overallTimer.stop();
    LOGGER.info("FINISHED!");
    stats.printSummary();
    stats.close();
  }

  public Arguments arguments() {
    return arguments;
  }

  public OsmInputFile osmInputFile() {
    return osmInputFile;
  }

  public CommonParams config() {
    return config;
  }

  public Stats stats() {
    return stats;
  }
}