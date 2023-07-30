package com.onthegomap.planetiler.stream;

import com.google.protobuf.ByteString;
import com.onthegomap.planetiler.archive.TileArchiveMetadata;
import com.onthegomap.planetiler.archive.TileEncodingResult;
import com.onthegomap.planetiler.geo.TileCoord;
import com.onthegomap.planetiler.proto.StreamArchiveProto;
import com.onthegomap.planetiler.util.LayerStats.VectorLayer;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;

/**
 * Writes protobuf-serialized tile data as well as meta data into file(s). The messages are of type
 * {@link StreamArchiveProto.Entry} and are length-delimited.
 */
public final class WriteableProtoStreamArchive extends WritableStreamArchive {

  private WriteableProtoStreamArchive(Path p, StreamArchiveConfig config) {
    super(p, config);
  }

  public static WriteableProtoStreamArchive newWriteToFile(Path path, StreamArchiveConfig config) {
    return new WriteableProtoStreamArchive(path, config);
  }

  @Override
  protected TileWriter newTileWriter(OutputStream outputStream) {
    return new ProtoTileArchiveWriter(outputStream);
  }

  @Override
  public void initialize(TileArchiveMetadata metadata) {
    writeEntry(
      StreamArchiveProto.Entry.newBuilder()
        .setInitialization(
          StreamArchiveProto.InitializationEntry.newBuilder().setMetadata(toExportData(metadata)).build()
        )
        .build()
    );
  }

  @Override
  public void finish(TileArchiveMetadata metadata) {
    writeEntry(
      StreamArchiveProto.Entry.newBuilder()
        .setFinish(
          StreamArchiveProto.FinishEntry.newBuilder().setMetadata(toExportData(metadata)).build()
        )
        .build()
    );
  }

  private void writeEntry(StreamArchiveProto.Entry entry) {
    try {
      entry.writeDelimitedTo(getPrimaryOutputStream());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static StreamArchiveProto.Metadata toExportData(TileArchiveMetadata metadata) {
    var metaDataBuilder = StreamArchiveProto.Metadata.newBuilder();
    setIfNotNull(metaDataBuilder::setName, metadata.name());
    setIfNotNull(metaDataBuilder::setDescription, metadata.description());
    setIfNotNull(metaDataBuilder::setAttribution, metadata.attribution());
    setIfNotNull(metaDataBuilder::setVersion, metadata.version());
    setIfNotNull(metaDataBuilder::setType, metadata.type());
    setIfNotNull(metaDataBuilder::setFormat, metadata.format());
    setIfNotNull(metaDataBuilder::setBounds, toExportData(metadata.bounds()));
    setIfNotNull(metaDataBuilder::setCenter, toExportData(metadata.center()));
    setIfNotNull(metaDataBuilder::setZoom, metadata.zoom());
    setIfNotNull(metaDataBuilder::setMinZoom, metadata.minzoom());
    setIfNotNull(metaDataBuilder::setMaxZoom, metadata.maxzoom());
    if (metadata.vectorLayers() != null) {
      metadata.vectorLayers().forEach(vl -> metaDataBuilder.addVectorLayers(toExportData(vl)));
    }
    if (metadata.others() != null) {
      metadata.others().forEach(metaDataBuilder::putOthers);
    }

    return metaDataBuilder.build();
  }

  private static StreamArchiveProto.Envelope toExportData(Envelope envelope) {
    if (envelope == null) {
      return null;
    }
    return StreamArchiveProto.Envelope.newBuilder()
      .setMinX(envelope.getMinX())
      .setMaxX(envelope.getMaxX())
      .setMinY(envelope.getMinY())
      .setMaxY(envelope.getMaxY())
      .build();
  }

  private static StreamArchiveProto.CoordinateXY toExportData(CoordinateXY coord) {
    if (coord == null) {
      return null;
    }
    return StreamArchiveProto.CoordinateXY.newBuilder()
      .setX(coord.getX())
      .setY(coord.getY())
      .build();
  }

  private static StreamArchiveProto.VectorLayer toExportData(VectorLayer vectorLayer) {
    final var builder = StreamArchiveProto.VectorLayer.newBuilder();
    builder.setId(vectorLayer.id());
    vectorLayer.fields().entrySet().forEach(e -> {
      var exportType = switch (e.getValue()) {
        case NUMBER -> StreamArchiveProto.VectorLayer.FieldType.FIELD_TYPE_NUMBER;
        case BOOLEAN -> StreamArchiveProto.VectorLayer.FieldType.FIELD_TYPE_BOOLEAN;
        case STRING -> StreamArchiveProto.VectorLayer.FieldType.FIELD_TYPE_STRING;
      };
      builder.putFields(e.getKey(), exportType);
    });
    vectorLayer.description().ifPresent(builder::setDescription);
    vectorLayer.minzoom().ifPresent(builder::setMinZoom);
    vectorLayer.maxzoom().ifPresent(builder::setMaxZoom);
    return builder.build();
  }

  private static <T> void setIfNotNull(Consumer<T> setter, T value) {
    if (value != null) {
      setter.accept(value);
    }
  }

  private static class ProtoTileArchiveWriter implements TileWriter {

    private final OutputStream out;

    ProtoTileArchiveWriter(OutputStream out) {
      this.out = out;
    }

    @Override
    public void write(TileEncodingResult encodingResult) {
      final TileCoord coord = encodingResult.coord();
      final StreamArchiveProto.TileEntry tile = StreamArchiveProto.TileEntry.newBuilder()
        .setZ(coord.z())
        .setX(coord.x())
        .setY(coord.y())
        .setEncodedData(ByteString.copyFrom(encodingResult.tileData()))
        .build();

      final StreamArchiveProto.Entry entry = StreamArchiveProto.Entry.newBuilder()
        .setTile(tile)
        .build();

      try {
        entry.writeDelimitedTo(out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void close() {
      try {
        out.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  // for quick testing
  // note: do not use nio (Files.newInputStream) for pipes
  public static void main(String[] args) throws IOException {
    try (var in = new FileInputStream(args[0])) {
      StreamArchiveProto.Entry entry;
      while ((entry = StreamArchiveProto.Entry.parseDelimitedFrom(in)) != null) {
        System.out.println(entry);
      }
    }
  }
}
