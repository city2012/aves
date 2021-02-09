import 'package:aves/model/entry.dart';
import 'package:aves/model/filters/album.dart';
import 'package:aves/model/settings/settings.dart';
import 'package:aves/model/source/collection_source.dart';
import 'package:aves/utils/android_file_utils.dart';
import 'package:collection/collection.dart';
import 'package:path/path.dart';

mixin AlbumMixin on SourceBase {
  final Set<String> _directories = {};

  List<String> get rawAlbums => List.unmodifiable(_directories);

  int compareAlbumsByName(String a, String b) {
    final ua = getUniqueAlbumName(a);
    final ub = getUniqueAlbumName(b);
    final c = compareAsciiUpperCase(ua, ub);
    if (c != 0) return c;
    final va = androidFileUtils.getStorageVolume(a)?.path ?? '';
    final vb = androidFileUtils.getStorageVolume(b)?.path ?? '';
    return compareAsciiUpperCase(va, vb);
  }

  void _notifyAlbumChange() => eventBus.fire(AlbumsChangedEvent());

  String getUniqueAlbumName(String album) {
    final otherAlbums = _directories.where((item) => item != album);
    final parts = album.split(separator);
    var partCount = 0;
    String testName;
    do {
      testName = separator + parts.skip(parts.length - ++partCount).join(separator);
    } while (otherAlbums.any((item) => item.endsWith(testName)));
    final uniqueName = parts.skip(parts.length - partCount).join(separator);

    final volume = androidFileUtils.getStorageVolume(album);
    if (volume == null) {
      return uniqueName;
    }

    final volumeRootLength = volume.path.length;
    if (album.length < volumeRootLength) {
      // `album` is at the root, without trailing '/'
      return uniqueName;
    }

    final albumRelativePath = album.substring(volumeRootLength);
    if (uniqueName.length < albumRelativePath.length) {
      return uniqueName;
    } else if (volume.isPrimary) {
      return albumRelativePath;
    } else {
      return '$albumRelativePath (${volume.description})';
    }
  }

  Map<String, AvesEntry> getAlbumEntries() {
    final entries = sortedEntriesByDate;
    final regularAlbums = <String>[], appAlbums = <String>[], specialAlbums = <String>[];
    for (final album in rawAlbums) {
      switch (androidFileUtils.getAlbumType(album)) {
        case AlbumType.regular:
          regularAlbums.add(album);
          break;
        case AlbumType.app:
          appAlbums.add(album);
          break;
        default:
          specialAlbums.add(album);
          break;
      }
    }
    return Map.fromEntries([...specialAlbums, ...appAlbums, ...regularAlbums].map((album) => MapEntry(
          album,
          entries.firstWhere((entry) => entry.directory == album, orElse: () => null),
        )));
  }

  void updateDirectories() {
    final visibleDirectories = visibleEntries.map((entry) => entry.directory).toSet();
    addDirectories(visibleDirectories);
    cleanEmptyAlbums();
  }

  void addDirectories(Set<String> albums) {
    if (!_directories.containsAll(albums)) {
      _directories.addAll(albums);
      _notifyAlbumChange();
    }
  }

  void cleanEmptyAlbums([Set<String> albums]) {
    final emptyAlbums = (albums ?? _directories).where(_isEmptyAlbum).toSet();
    if (emptyAlbums.isNotEmpty) {
      _directories.removeAll(emptyAlbums);
      _notifyAlbumChange();
      invalidateAlbumFilterSummary(directories: emptyAlbums);

      final pinnedFilters = settings.pinnedFilters;
      emptyAlbums.forEach((album) => pinnedFilters.remove(AlbumFilter(album, getUniqueAlbumName(album))));
      settings.pinnedFilters = pinnedFilters;
    }
  }

  bool _isEmptyAlbum(String album) => !visibleEntries.any((entry) => entry.directory == album);

  // filter summary

  // by directory
  final Map<String, int> _filterEntryCountMap = {};
  final Map<String, AvesEntry> _filterRecentEntryMap = {};

  void invalidateAlbumFilterSummary({Set<AvesEntry> entries, Set<String> directories}) {
    if (entries == null && directories == null) {
      _filterEntryCountMap.clear();
      _filterRecentEntryMap.clear();
    } else {
      directories ??= entries.map((entry) => entry.directory).toSet();
      directories.forEach(_filterEntryCountMap.remove);
      directories.forEach(_filterRecentEntryMap.remove);
    }
  }

  int albumEntryCount(AlbumFilter filter) {
    return _filterEntryCountMap.putIfAbsent(filter.album, () => visibleEntries.where((entry) => filter.filter(entry)).length);
  }

  AvesEntry albumRecentEntry(AlbumFilter filter) {
    return _filterRecentEntryMap.putIfAbsent(filter.album, () => sortedEntriesByDate.firstWhere((entry) => filter.filter(entry), orElse: () => null));
  }
}

class AlbumsChangedEvent {}
