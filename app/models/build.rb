## The canonical Build model lives in the clojure code. This definition exists to make Rail's querying easier
class Build
  include Mongoid::Document

  field :vcs_url
  field :vcs_revision

  field :failed, :type => Boolean, :default => nil

  field :start_time, :type => Time, :default => nil
  field :stop_time, :type => Time, :default => nil

  field :build_num, :type => Integer
  field :parents, :type => Array, :default => nil
  field :subject, :type => String, :default => nil
  field :body, :type => String, :default => nil
  field :branch, :type => String, :default => nil

  field :committer_name, :type => String, :default => nil
  field :committer_email, :type => String, :default => nil
  field :committer_date, :type => Time, :default => nil
  field :author_email, :type => String, :default => nil
  field :author_name, :type => String, :default => nil
  field :author_date, :type => Time, :default => nil

#  belongs_to :project

  has_many :action_logs, :inverse_of => :thebuild

  def the_project
    # TECHNICAL_DEBT: the clojure and ruby models are not synced, so we don't
    # have access to the build model directly
    Project.where(:vcs_url => vcs_url).first
  end

  def project
    the_project
  end

  def self.start(url)
    Build.create! :start_time => Time.now, :vcs_url => url
  end

  def logs
    if action_logs.length > 0
      action_logs
    else
      ActionLog.where("_build-ref" => id).all
    end
  end

  def started
    status != :starting
  end

  def status
    if failed
      :fail
    elsif stop_time
      :success
    elsif (start_time + 3.hours) < Time.now
      :killed
    elsif build_num != nil
      :running
    else
      :starting
    end
  end
end
