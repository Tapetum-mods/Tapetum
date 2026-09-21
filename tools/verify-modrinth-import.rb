require 'json'
require 'digest'
require 'open3'

# Read-only preparation. The user imports through Modrinth; never write its managed profiles.
root = File.expand_path('..', __dir__)
base = File.expand_path('~/Library/Application Support/ModrinthApp')
database = File.join(base, 'app.db')
targets = [['mc26.1', '26.1.2', 'Tapetum'], ['mc26.2', '26.2', 'Tapetum 26.1.2']]

def rows(database, sql)
  output, error, status = Open3.capture3('sqlite3', '-readonly', '-json', database, sql)
  abort(error) unless status.success?
  output.strip.empty? ? [] : JSON.parse(output)
end

pending = targets.map do |mod, version, profile|
  installed = rows(database, "SELECT c.game_version,c.loader FROM instances i " \
    "JOIN instance_content_sets c ON c.id=i.applied_content_set_id WHERE i.path='#{profile}';")
  abort("Wrong game/loader for #{profile}") unless installed == [{'game_version' => version, 'loader' => 'fabric'}]
  name = "tapetum-shaders-0.1.0+mc#{version}.jar"
  tracked = rows(database, "SELECT f.relative_path FROM instance_files f JOIN instances i ON i.id=f.instance_id " \
    "WHERE i.path='#{profile}' AND f.file_name LIKE 'tapetum-shaders%';")
  abort("#{profile}: Tapetum is managed already; re-import through Modrinth, do not overwrite") unless tracked.empty?
  directory = File.join(base, 'profiles', profile)
  abort("Existing Tapetum file in #{profile}; refusing duplicate/overwrite") unless Dir[File.join(directory, 'mods', '*tapetum*.jar*')].empty?
  source = File.join(root, mod, 'build', 'libs', name)
  metadata, error, status = Open3.capture3('unzip', '-p', source, 'fabric.mod.json')
  abort(error) unless status.success?
  metadata = JSON.parse(metadata)
  abort('Wrong standalone artifact') unless metadata['id'] == 'tapetumshaders' &&
    metadata['version'] == "0.1.0+mc#{version}" && !metadata.fetch('depends').key?('sodium') &&
    !metadata.fetch('depends').key?('iris')
  _, error, status = Open3.capture3('unzip', '-tq', source)
  abort(error) unless status.success?
  [source, File.join(directory, 'mods', name), directory]
end

pending.each do |source, destination, _directory|
  puts "Ready to import: #{source}\nTarget: #{destination}\nSHA256 #{Digest::SHA256.file(source).hexdigest}"
end
puts 'Read-only verification complete. No profiles changed; import using Modrinth.'
