# AppAuth with Dynamic Client Registration

An extended Android AppAuth code example using authenticated Dynamic Client Registration.\
This improves the mobile app's security as described in [Mobile Best Practices](https://curity.io/resources/learn/oauth-for-mobile-apps-best-practices/).

## Tutorial Documentation

The [Tutorial Walkthrough](https://curity.io/resources/learn/resources/authenticated-dcr-example) explains the complete configuration and behavior.

## Quick Start

The easiest way to run the code example is via an automated script as explained in the [Mobile Setup Article](https://curity.io/resources/learn/mobile-setup-ngrok):

- Copy a license.json file into the code example root folder
- Edit the `./start-idsvr.sh` script to use either a local Docker URL on an ngrok internet URL
- Run the script to deploy a preconfigured Curity Identity Server via Docker
- Build and run the mobile app from Android Studio
- Sign in with the preconfigured user account `demouser / Password1`
- Run `./stop-idsvr.sh` when you want to free Docker resources

## User Experience

When the user first runs the app there is a prompt to authenticate.\
This gets an initial access token with the `dcr` scope.\
This is then used to create a Dynamic Client, which returns a Client ID and Client Secret.

![images](/images/registration-view.png)

The user must then authenticate again, and this is automatic via Single Sign On.\
On all subsequent authentication requests the user only needs to sign in once:

![images](/images/unauthenticated-view.png)

Once authenticated, the user is moved to the authenticated view.\
The demo app simply allows other OAuth lifecycle events to be tested.

![images](/images/authenticated-view.png)

## Manage Registration Details

To view all registered mobile instances, first connect to the Identity Server's SQL database:

```bash
export DB_CONTAINER_ID=$(docker container ls | grep curity-data | awk '{print $1}')
docker exec -it $DB_CONTAINER_ID bash -c "export PGPASSWORD=Password1 && psql -p 5432 -d idsvr -U postgres"
```

Then query the details of the dynamically registered mobile clients:

```bash
select * from dynamically_registered_clients;
```

## More Information

Please visit [curity.io](https://curity.io/) for more information about the Curity Identity Server.
