require 'json'
require 'open3'
require_relative 'verify-artifact'

# Read-only preparation; managed profiles are modified only by the launcher's import workflow.
abort 'Usage: ruby tools/verify-modrinth-import.rb PRODUCTION_JAR' unless ARGV.length == 1
artifact = TapetumArtifact.verify(ARGV.first)
puts JSON.pretty_generate(artifact)
profile = {'26.1.2' => 'Tapetum', '26.2' => 'Tapetum 26.1.2'}[artifact[:game]]
unless profile
  puts 'No profile selected for this game version. Choose one in the launcher; no files were changed.'
  exit
end

base = File.expand_path('~/Library/Application Support/ModrinthApp')
database = File.join(base, 'app.db')
abort 'Modrinth database not found; no profile was modified' unless File.file?(database)

def rows(database, sql)
  output, error, status = Open3.capture3('sqlite3', '-readonly', '-json', database, sql)
  abort(error) unless status.success?
  output.strip.empty? ? [] : JSON.parse(output)
end

installed = rows(database, "SELECT c.game_version,c.loader FROM instances i " \
  "JOIN instance_content_sets c ON c.id=i.applied_content_set_id WHERE i.path='#{profile}';")
abort("Wrong game/loader for #{profile}") unless installed == [{'game_version' => artifact[:game], 'loader' => 'fabric'}]
tracked = rows(database, "SELECT f.relative_path FROM instance_files f JOIN instances i ON i.id=f.instance_id " \
  "WHERE i.path='#{profile}' AND f.file_name LIKE 'tapetum-shaders%';")
puts "Target profile: #{profile} (Minecraft #{artifact[:game]}, Fabric)"
puts "Existing managed Tapetum entries: #{tracked.length}; use the launcher's replacement/import flow."
puts 'Read-only verification complete. No profiles changed. Restart Minecraft after import.'
