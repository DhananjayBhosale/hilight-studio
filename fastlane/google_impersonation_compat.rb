require "googleauth/impersonated_service_account"

# Fastlane 2.240.1 Supply eagerly calls fetch_access_token!. Googleauth 1.17.4
# marks that method private on impersonated credentials although it supports ADC.
Google::Auth::ImpersonatedServiceAccountCredentials.class_eval do
  public :fetch_access_token!
end
